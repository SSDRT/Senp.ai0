"""UI-PRMD Vicon39 marker-angle research adapter, not an MP33 pose adapter.

Markers follow Table 2 (Positions, not Angles) of doi:10.3390/data3010002.
Angles use surface marker proxies, not reconstructed anatomical joint centres.
Finite nonzero samples are observed measurements, not MediaPipe confidence.
No interpolation, smoothing, normalisation, inverse kinematics or label fitting.
"""
import hashlib
import numpy as np

PIPELINE = 'uiprmd-vicon39-marker-angle/1'
SAMPLE_PERIOD_MS = 10  # Vicon acquisition 100 Hz; paper section 2.2.
SEGMENT_EXPORT_TOLERANCE_MM = 1e-6  # absolute only: publisher text-export rounding, no time warp.
MARKERS = ('LFHD RFHD LBHD RBHD C7 T10 CLAV STRN RBAK '
           'LSHO LUPA LELB LFRM LWRA LWRB LFIN RSHO RUPA RELB RFRM RWRA RWRB RFIN '
           'LASI RASI LPSI RPSI LTHI LKNE LTIB LANK LHEE LTOE RTHI RKNE RTIB RANK RHEE RTOE').split()
INDEX = {name: i for i, name in enumerate(MARKERS)}
# Explicit acquisition/geometry identity; this is not a landmark-network hash.
BACKEND_SHA256 = hashlib.sha256((PIPELINE + '|100Hz|mm|globalXYZ|' + ','.join(MARKERS)).encode()).hexdigest()


def markers(array):
    array = np.asarray(array, dtype=float)
    if array.ndim != 2 or array.shape[1] != 117:
        raise ValueError('Expected complete Vicon39 XYZ rows (117 columns)')
    return array.reshape(-1, 39, 3)


def angle(a, b, c):
    """Angle ABC in degrees. Invalid or degenerate rays stay missing."""
    valid = np.isfinite(a).all(axis=-1) & np.isfinite(b).all(axis=-1) & np.isfinite(c).all(axis=-1)
    # All-zero marker triples are missing acquisition samples, not an origin joint.
    valid &= (np.linalg.norm(a, axis=-1) > 0) & (np.linalg.norm(b, axis=-1) > 0) & (np.linalg.norm(c, axis=-1) > 0)
    u, v = a-b, c-b
    denominator = np.linalg.norm(u, axis=-1)*np.linalg.norm(v, axis=-1)
    valid &= denominator > 1e-12
    cosine = np.divide(np.sum(u*v, axis=-1), denominator, out=np.zeros_like(denominator), where=valid)
    result = np.degrees(np.arccos(np.clip(cosine, -1, 1)))
    return np.where(valid, result, np.nan)


def measurement_features(array):
    points = markers(array)
    def p(name):
        return points[:, INDEX[name]]
    result = {}
    for side, prefix in [('left', 'L'), ('right', 'R')]:
        shoulder, elbow, hip, knee, ankle = [p(prefix+name) for name in ['SHO', 'ELB', 'ASI', 'KNE', 'ANK']]
        wrist_a, wrist_b = p(prefix+'WRA'), p(prefix+'WRB')
        wrist = (wrist_a+wrist_b)/2
        wrist[~(np.isfinite(wrist_a).all(axis=-1) & np.isfinite(wrist_b).all(axis=-1) &
                (np.linalg.norm(wrist_a, axis=-1) > 0) & (np.linalg.norm(wrist_b, axis=-1) > 0))] = np.nan
        result['angle.'+side+'_knee'] = angle(hip, knee, ankle)
        result['angle.'+side+'_hip'] = angle(shoulder, hip, knee)
        result['angle.'+side+'_shoulder'] = angle(elbow, shoulder, hip)
        result['angle.'+side+'_elbow'] = angle(shoulder, elbow, wrist)
    return result


def aligned_segment_bounds(full, segment):
    """Unique contiguous excerpt within 1e-6 mm absolute export rounding; no resampling.

    The publisher's segmented/full text serialisations differ by up to a few 1e-7
    mm on near-zero values. Every coordinate of every frame must agree within the
    fixed bound; approximate nearest poses or timing adjustments are forbidden.
    """
    full, segment = np.asarray(full), np.asarray(segment)
    if full.ndim != 2 or segment.ndim != 2 or full.shape[1] != segment.shape[1] or not len(segment):
        raise ValueError('Invalid segmentation arrays')
    candidates = np.flatnonzero(np.all(np.abs(full-segment[0]) <= SEGMENT_EXPORT_TOLERANCE_MM, axis=1))
    matches = [int(i) for i in candidates if i+len(segment) <= len(full) and
               np.all(np.abs(full[i:i+len(segment)]-segment) <= SEGMENT_EXPORT_TOLERANCE_MM)]
    if len(matches) != 1:
        raise ValueError(f'Segment must be a unique contiguous excerpt within export rounding, found {len(matches)}')
    start = matches[0]
    return start, start+len(segment)-1
