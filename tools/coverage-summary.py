#!/usr/bin/env python3
"""Prints the line coverage per file and the uncovered line numbers from a JaCoCo XML report."""
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for pkg in root.iter("package"):
    for sf in pkg.iter("sourcefile"):
        c = next((x for x in sf.findall("counter") if x.get("type") == "LINE"), None)
        if c is None:
            continue
        missed, covered = int(c.get("missed")), int(c.get("covered"))
        lines = [l.get("nr") for l in sf.findall("line") if int(l.get("ci")) == 0 and int(l.get("mi")) > 0]
        print(f"{sf.get('name')}: {covered}/{missed + covered} lines; uncovered: {' '.join(lines)}")
for c in root.findall("counter"):
    if c.get("type") == "LINE":
        m, cv = int(c.get("missed")), int(c.get("covered"))
        print(f"TOTAL {cv}/{m + cv} = {100 * cv / (m + cv):.1f} %")
