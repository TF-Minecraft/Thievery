#!/usr/bin/env python3
"""Fail the build when Surefire produced no reports or skipped any test."""
from pathlib import Path
import xml.etree.ElementTree as ET

reports = list(Path("target/surefire-reports").glob("TEST-*.xml"))
if not reports:
    raise SystemExit("No test reports produced")
skipped = sum(int(ET.parse(report).getroot().get("skipped", "0")) for report in reports)
if skipped:
    raise SystemExit(f"{skipped} tests skipped; coverage requires runnable scenarios")
