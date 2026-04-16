#!/usr/bin/env python3
"""
Scrapes Smogon monthly usage stats (chaos JSON) and produces a compact
JSON file for the auto-qiqi mod.

Usage:
    python scrape_smogon.py                          # latest month, gen9ou, 1825 Elo
    python scrape_smogon.py --month 2026-03
    python scrape_smogon.py --gen gen9ou --elo 1500
    python scrape_smogon.py --top 100                # only keep top N Pokemon by usage

Output goes to: auto-qiqi/src/main/resources/assets/auto-qiqi/smogon_ou.json
"""

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

SMOGON_STATS_BASE = "https://www.smogon.com/stats"
OUTPUT_DIR = Path(__file__).resolve().parent.parent / "auto-qiqi" / "src" / "main" / "resources" / "assets" / "auto-qiqi"

def discover_latest_month():
    """Scrape the stats index page to find the most recent month."""
    url = f"{SMOGON_STATS_BASE}/"
    with urllib.request.urlopen(url, timeout=15) as resp:
        html = resp.read().decode()
    months = re.findall(r'href="(\d{4}-\d{2})/"', html)
    if not months:
        raise RuntimeError("Could not find any month directories on Smogon stats page")
    months.sort()
    return months[-1]

def fetch_chaos_json(month: str, gen: str, elo: int) -> dict:
    """Download the chaos JSON for the given parameters."""
    url = f"{SMOGON_STATS_BASE}/{month}/chaos/{gen}-{elo}.json"
    print(f"Fetching {url} ...")
    req = urllib.request.Request(url, headers={"Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read()
        # Handle gzip if server sends it
        if resp.headers.get("Content-Encoding") == "gzip":
            import gzip
            raw = gzip.decompress(raw)
        return json.loads(raw)

def normalize_name(name: str) -> str:
    """Normalize Pokemon/move names to lowercase alphanumeric (matching Cobblemon conventions)."""
    return re.sub(r'[^a-z0-9]', '', name.lower())

def top_entries(d, n):
    """Return top N entries from a {name: weight} dict, sorted by weight descending."""
    sorted_items = sorted(d.items(), key=lambda x: x[1], reverse=True)
    return sorted_items[:n]

def parse_spread(spread_str: str) -> dict:
    """Parse a Smogon spread string like 'Jolly:0/252/4/0/0/252' into a dict."""
    parts = spread_str.split(":")
    if len(parts) != 2:
        return None
    nature = parts[0]
    evs = parts[1].split("/")
    if len(evs) != 6:
        return None
    labels = ["hp", "atk", "def", "spa", "spd", "spe"]
    return {
        "nature": nature,
        **{labels[i]: int(evs[i]) for i in range(6)}
    }

def extract_role(moves: list[str], items: list[str], spreads: list[dict]) -> list[str]:
    """Infer competitive roles from common moves, items, and spreads."""
    roles = []
    move_set = {normalize_name(m) for m in moves}
    item_set = {normalize_name(i) for i in items}

    # Setup sweeper detection
    setup_moves = {"swordsdance", "dragondance", "nastyplot", "calmmind", "shellsmash",
                   "quiverdance", "bulkup", "irondefense", "bodypress", "coil",
                   "shiftgear", "tailglow", "growth", "workup", "victorydance"}
    if move_set & setup_moves:
        roles.append("SETUP")

    # Choice user detection
    choice_items = {"choicescarf", "choiceband", "choicespecs"}
    if item_set & choice_items:
        roles.append("CHOICE")
        if "choicescarf" in item_set:
            roles.append("REVENGE_KILLER")

    # Hazard setter
    hazard_moves = {"stealthrock", "spikes", "toxicspikes", "stickyweb"}
    if move_set & hazard_moves:
        roles.append("HAZARD_SETTER")

    # Hazard removal
    removal_moves = {"rapidspin", "defog", "tidyup", "mortalspin", "courtchange"}
    if move_set & removal_moves:
        roles.append("HAZARD_REMOVAL")

    # Wall / tank
    wall_moves = {"recover", "roost", "softboiled", "slackoff", "wish", "synthesis",
                  "moonlight", "shoreup", "strengthsap"}
    if move_set & wall_moves:
        # Check if EV spread is defensive
        if spreads:
            top_spread = spreads[0]
            if top_spread and (top_spread.get("hp", 0) >= 200 or top_spread.get("def", 0) >= 200 or top_spread.get("spd", 0) >= 200):
                roles.append("WALL")

    # Pivot
    pivot_moves = {"uturn", "voltswitch", "flipturn", "partingshot", "teleport", "chillyreception"}
    if move_set & pivot_moves:
        roles.append("PIVOT")

    # Wallbreaker (high attack EVs + Life Orb / Choice Band/Specs, no setup)
    breaker_items = {"lifeorb", "choiceband", "choicespecs"}
    if item_set & breaker_items and not (move_set & setup_moves):
        if spreads:
            top_spread = spreads[0]
            if top_spread and (top_spread.get("atk", 0) >= 252 or top_spread.get("spa", 0) >= 252):
                roles.append("WALLBREAKER")

    if not roles:
        roles.append("GENERAL")

    return roles

def compute_speed_tiers(spread):
    """Compute approximate speed stat for a spread (base stat not known here, added at runtime)."""
    if not spread:
        return None
    return {
        "nature": spread["nature"],
        "spe_ev": spread.get("spe", 0),
    }

def transform(raw: dict, top_n: int) -> dict:
    """Transform raw Smogon chaos JSON into our compact format."""
    info = raw.get("info", {})
    data = raw.get("data", {})

    # Sort by usage (Raw count)
    sorted_pokemon = sorted(data.items(), key=lambda x: x[1].get("Raw count", 0), reverse=True)
    if top_n > 0:
        sorted_pokemon = sorted_pokemon[:top_n]

    result = {
        "meta": {
            "metagame": info.get("metagame", "gen9ou"),
            "month": info.get("cutoff", 1825),
            "battles": info.get("number of battles", 0),
        },
        "pokemon": {}
    }

    for species_name, pdata in sorted_pokemon:
        # Top 6 moves
        moves_raw = pdata.get("Moves", {})
        top_moves = top_entries(moves_raw, 6)
        move_names = [m[0] for m in top_moves]
        total_moves = sum(moves_raw.values()) if moves_raw else 1
        move_pcts = {m[0]: round(m[1] / total_moves * 100, 1) for m in top_moves}

        # Top 4 items
        items_raw = pdata.get("Items", {})
        top_items = top_entries(items_raw, 4)
        total_items = sum(items_raw.values()) if items_raw else 1
        item_pcts = {i[0]: round(i[1] / total_items * 100, 1) for i in top_items}

        # Top 3 abilities
        abilities_raw = pdata.get("Abilities", {})
        top_abilities = top_entries(abilities_raw, 3)
        total_abilities = sum(abilities_raw.values()) if abilities_raw else 1
        ability_pcts = {a[0]: round(a[1] / total_abilities * 100, 1) for a in top_abilities}

        # Top 3 spreads
        spreads_raw = pdata.get("Spreads", {})
        top_spreads_raw = top_entries(spreads_raw, 3)
        spreads = []
        for s_str, _ in top_spreads_raw:
            parsed = parse_spread(s_str)
            if parsed:
                spreads.append(parsed)

        # Top 6 teammates
        teammates_raw = pdata.get("Teammates", {})
        top_teammates = top_entries(teammates_raw, 6)
        teammate_names = [t[0] for t in top_teammates]

        # Roles
        roles = extract_role(move_names, [i[0] for i in top_items], spreads)

        # Speed info from top spread
        speed_info = compute_speed_tiers(spreads[0] if spreads else None)

        # Normalized key for lookup
        key = normalize_name(species_name)

        result["pokemon"][key] = {
            "name": species_name,
            "roles": roles,
            "moves": move_pcts,
            "items": item_pcts,
            "abilities": ability_pcts,
            "spreads": spreads[:2],  # Keep top 2 spreads
            "teammates": teammate_names,
        }
        if speed_info:
            result["pokemon"][key]["speed"] = speed_info

    return result

## Gen presets: gen -> (metagame id, best available Elo cutoff)
GEN_PRESETS = {
    "gen9": ("gen9ou", 1825),
    "gen8": ("gen8ou", 1760),
    "gen7": ("gen7ou", 1760),
}

def scrape_one(month, gen_key, elo, top_n, output_path):
    """Scrape one metagame and write the output JSON."""
    raw = fetch_chaos_json(month, gen_key, elo)
    transformed = transform(raw, top_n)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(transformed, f, indent=2, ensure_ascii=False)
    n_pokemon = len(transformed["pokemon"])
    size_kb = output_path.stat().st_size / 1024
    print(f"  -> {n_pokemon} Pokemon, {size_kb:.0f} KB -> {output_path.name}")

def main():
    parser = argparse.ArgumentParser(description="Scrape Smogon usage stats for auto-qiqi")
    parser.add_argument("--month", type=str, default=None, help="Stats month (e.g. 2026-03). Default: latest.")
    parser.add_argument("--gen", type=str, default=None,
                        help="Single metagame id (e.g. gen9ou). Overrides --gens.")
    parser.add_argument("--gens", type=str, default="gen9,gen8,gen7",
                        help="Comma-separated gen keys to scrape (default: gen9,gen8,gen7)")
    parser.add_argument("--elo", type=int, default=None,
                        help="Elo cutoff override (default: use best for each gen)")
    parser.add_argument("--top", type=int, default=150,
                        help="Keep top N Pokemon by usage (default: 150, 0 = all)")
    parser.add_argument("--output", type=str, default=None, help="Output path (single gen mode only)")
    args = parser.parse_args()

    month = args.month or discover_latest_month()
    print(f"Using month: {month}")

    if args.gen:
        # Single gen mode (backwards compatible)
        elo = args.elo or 1825
        output_path = Path(args.output) if args.output else OUTPUT_DIR / "smogon_ou.json"
        scrape_one(month, args.gen, elo, args.top, output_path)
    else:
        # Multi-gen mode
        gens = [g.strip() for g in args.gens.split(",")]
        for gen in gens:
            if gen not in GEN_PRESETS:
                print(f"Unknown gen '{gen}', skipping (known: {list(GEN_PRESETS.keys())})")
                continue
            meta, default_elo = GEN_PRESETS[gen]
            elo = args.elo or default_elo
            # gen9 -> smogon_ou.json, gen8 -> smogon_gen8ou.json, gen7 -> smogon_gen7ou.json
            filename = "smogon_ou.json" if gen == "gen9" else f"smogon_{meta}.json"
            output_path = OUTPUT_DIR / filename
            print(f"Scraping {meta} (Elo {elo})...")
            scrape_one(month, meta, elo, args.top, output_path)

    print("Done!")

if __name__ == "__main__":
    main()
