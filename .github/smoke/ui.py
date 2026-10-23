"""Tiny helpers over `uiautomator dump` XML.

  ui.py has  DUMP TEXT   -> exit 0 if any node's text/content-desc contains TEXT (case-insensitive)
  ui.py find DUMP TEXT   -> print "x y" centre of the first node whose text/content-desc equals TEXT
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(path):
    try:
        return list(ET.parse(path).getroot().iter("node"))
    except (ET.ParseError, FileNotFoundError):
        return []


def labels(node):
    return [(node.get("text") or "").strip(), (node.get("content-desc") or "").strip()]


cmd, path, text = sys.argv[1], sys.argv[2], sys.argv[3].strip().lower()
if cmd == "has":
    sys.exit(0 if any(text in l.lower() for n in nodes(path) for l in labels(n)) else 1)
if cmd == "find":
    for n in nodes(path):
        if any(l.lower() == text for l in labels(n)):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
    sys.exit(1)
sys.exit(2)
