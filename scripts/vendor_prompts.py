#!/usr/bin/env python3
"""One-time deterministic CC0 import; generated files are committed for offline use.

Run: python3 scripts/vendor_prompts.py
No runtime/network dependency is introduced into the Android application.
"""
import csv
import hashlib
import io
import json
import re
import urllib.request
from collections import Counter
from pathlib import Path

UPSTREAM_COMMIT = "bd4ed9313fdb1204e8c256d709d13694f72581de"
UPSTREAM_BLOB = "f869b9947ed309d180f737bbd724ff0496777f62"
SOURCE = f"https://raw.githubusercontent.com/f/prompts.chat/{UPSTREAM_COMMIT}/prompts.csv"
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/todd_prompts.tsv"
MANIFEST = ROOT / "app/src/main/assets/todd_prompts_manifest.json"
TARGET = 200
# An independent, diverse offline catalog; a category is chosen per request.
QUOTAS = {
    "coding": 65,
    "mobile": 25,
    "research": 35,
    "translation": 20,
    "planning": 25,
    "automation": 20,
    "voice": 10,
}
KEYWORDS = {
    "mobile": r"android|ios|mobile|kotlin|flutter|react native|swift|app developer|ux designer|ui designer|interface design",
    "coding": r"programmer|developer|software|coding|code|debug|engineer|compiler|database|devops|architect|technical writer|cybersecurity|git|tester|quality assurance",
    "research": r"research|investigat|fact.?check|analyst|data scien|data analysis|scientist|scholar|search engine|journalist|evidence|historian",
    "translation": r"translat|linguist|language|proofread|grammar|english teacher|editor|copy.?edit|localiz",
    "planning": r"project manag|product manag|planner|strateg|consultant|coach|scrum|product owner|team lead|business analy|organizer",
    "automation": r"automati|workflow|agent|assistant|task manager|automation|system administrat|linux terminal|cli|shell",
    "voice": r"speech|voice|audio|conversation|dialogue|interviewer|podcast|speaking|storyteller",
}
UNSAFE = re.compile(
    r"ignore (all )?(previous|prior) instructions|jailbreak|bypass (security|safety)|"
    r"steal (password|credential)|disable (safety|security)", re.I
)

def run():
    with urllib.request.urlopen(
        urllib.request.Request(SOURCE, headers={"User-Agent": "Todd-offline-prompt-import/1"}),
        timeout=90
    ) as response:
        payload = response.read()
    blob_sha = hashlib.sha1(f"blob {len(payload)}\0".encode() + payload).hexdigest()
    if blob_sha != UPSTREAM_BLOB:
        raise RuntimeError("Upstream CSV bytes differ from pinned Git blob; refusing unverified import")

    data = list(csv.DictReader(io.StringIO(payload.decode("utf-8-sig"), newline="")))
    if not data or not {"act", "prompt"}.issubset(data[0]):
        raise RuntimeError("Expected act,prompt columns in official prompts.csv")

    candidates = []
    dedup = set()
    for row in data:
        title = (row.get("act") or "").strip().replace("\t", " ").replace("\n", " ")
        body = (row.get("prompt") or "").strip()
        normalized = re.sub(r"\s+", " ", body).lower()
        if not (3 <= len(title) <= 100 and 100 <= len(body) <= 4000):
            continue
        if UNSAFE.search(body) or UNSAFE.search(title) or normalized in dedup:
            continue
        dedup.add(normalized)
        labels = [category for category, words in KEYWORDS.items() if re.search(words, title, re.I)]
        if not labels:
            labels = [category for category, words in KEYWORDS.items()
                      if re.search(words, body[:350], re.I)]
        if not labels:
            continue
        for cat in labels:
            score = (80 if re.search(KEYWORDS[cat], title, re.I) else 5)
            score += 15 if 250 <= len(body) <= 1800 else 0
            score += min(10, len(re.findall(r"\b(step|example|analy[sz]e|review|explain|verify|test)\b", body, re.I)))
            candidates.append((score, cat, title, body, normalized))

    selected, used = [], set()
    for category, quota in QUOTAS.items():
        choices = sorted((row for row in candidates if row[1] == category), key=lambda a: (-a[0], a[2]))
        for _, cat, title, body, normalized in choices:
            if normalized in used:
                continue
            selected.append((cat, title, body))
            used.add(normalized)
            if sum(1 for x in selected if x[0] == category) >= quota:
                break
    if len(selected) < TARGET:
        for _, cat, title, body, normalized in sorted(candidates, key=lambda a: (-a[0], a[2])):
            if normalized in used:
                continue
            selected.append((cat, title, body))
            used.add(normalized)
            if len(selected) >= TARGET:
                break
    if len(selected) < 150:
        raise RuntimeError(f"Found only {len(selected)} relevant unique prompts; requires manual review")

    selected = selected[:TARGET]
    DEST.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Source: https://github.com/f/prompts.chat (prompts.csv), CC0; pinned commit " + UPSTREAM_COMMIT]
    for idx, (cat, title, body) in enumerate(selected, start=1):
        cleaned = body.replace("\r", "").replace("\t", " ").replace("\n", r"\n")
        lines.append(f"pc-{idx:03d}\t{cat}\t{title}\t{cleaned}")
    DEST.write_text("\n".join(lines) + "\n", encoding="utf-8")
    category_counts = dict(sorted(Counter(row[0] for row in selected).items()))
    manifest = {
        "source": SOURCE,
        "upstream_csv_git_blob_sha1": UPSTREAM_BLOB,
        "license_of_imported_prompts": "CC0",
        "total_selected": len(selected),
        "categories": category_counts,
        "purpose": "Locally bundled, advisory patterns only; no runtime calls or elevation of permissions",
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # Read-back integrity checks, no external dependencies besides Python.
    check = DEST.read_text("utf-8").splitlines()
    assert len(check) == len(selected) + 1
    assert all(line.count("\t") == 3 for line in check[1:])
    print(json.dumps(manifest, ensure_ascii=False))

if __name__ == "__main__":
    run()
