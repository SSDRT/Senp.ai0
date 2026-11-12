"""Mechanical three-point geometry, with no anatomical or tracking inference."""
import math
from decimal import Decimal


def triangle_angles(points):
    """All original vertices in order; no best-vertex or clinical-axis selection.

    A point at the coordinate origin is valid mathematics, not proof of tracking.
    Missing/nonfinite/boolean values and coincident points remain unassessable.
    Positive scaling and rigid transforms do not change the measured angles.
    """
    if not isinstance(points, (tuple, list)) or len(points) != 3:
        return None
    numeric = (int, float, Decimal)
    if any(not isinstance(p, (tuple, list)) or len(p) != 3 for p in points):
        return None
    if any(isinstance(v, bool) or not isinstance(v, numeric) or not math.isfinite(v)
           for p in points for v in p):
        return None
    original = [[float(v) for v in p] for p in points]
    angles = []
    for i in range(3):
        vectors = [[original[j][k]-original[i][k] for k in range(3)] for j in range(3) if j != i]
        lengths = [math.hypot(*v) for v in vectors]
        if any(not math.isfinite(length) or length == 0 for length in lengths):
            return None
        a, b = [[x/length for x in v] for v, length in zip(vectors, lengths)]
        cross = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
        dot = sum(x*y for x,y in zip(a,b))
        angles.append(math.degrees(math.atan2(math.hypot(*cross), dot)))
    return tuple(angles)
