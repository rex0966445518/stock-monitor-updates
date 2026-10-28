"""Four-digit quote universe; longer instrument codes remain navigation anchors only."""
import re

def scannable(code):
    return isinstance(code,str) and bool(re.fullmatch(r'[0-9]{4}',code))

def navigation_code(code):
    return isinstance(code,str) and bool(re.fullmatch(r'[0-9]{4,12}[A-Za-z]{0,2}',code))

def excluded_only(codes):
    return bool(codes) and all(navigation_code(c) and not scannable(c) for c in codes)
