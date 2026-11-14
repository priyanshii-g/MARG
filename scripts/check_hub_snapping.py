import json
import math

def min_dist(pt, segments):
    # pt = [lon, lat]
    min_d = float('inf')
    best_p = None
    for seg in segments:
        for i in range(1, len(seg)):
            p1 = seg[i-1]
            p2 = seg[i]
            # distance from pt to segment p1-p2
            # approximate meters at lat
            mid_lat = (p1[1] + p2[1]) / 2.0
            scale_x = 111320.0 * math.cos(math.radians(mid_lat))
            scale_y = 110540.0
            
            x0 = (pt[0] - p1[0]) * scale_x
            y0 = (pt[1] - p1[1]) * scale_y
            x1 = (p2[0] - p1[0]) * scale_x
            y1 = (p2[1] - p1[1]) * scale_y
            
            len2 = x1*x1 + y1*y1
            if len2 == 0:
                t = 0
            else:
                t = max(0.0, min(1.0, (x0*x1 + y0*y1) / len2))
            
            proj_x = p1[0] + t * (p2[0] - p1[0])
            proj_y = p1[1] + t * (p2[1] - p1[1])
            
            dist = math.hypot((pt[0] - proj_x) * scale_x, (pt[1] - proj_y) * scale_y)
            if dist < min_d:
                min_d = dist
                best_p = (round(proj_x, 5), round(proj_y, 5))
    return min_d, best_p

# 1. Test Bokakhat tieIn on NH-715
with open('scripts/nh715_assam_ways.json', 'r', encoding='utf-8') as f:
    nh715 = json.load(f)
d, p = min_dist([93.507, 26.612], nh715)
print(f"Bokakhat tie-in to real NH-715: distance={d:.1f}m, nearest point={p}")

# 2. Test Western Ghats Shirwal tieIn on NH-48
with open('scripts/nh48_wg_ways.json', 'r', encoding='utf-8') as f:
    nh48 = json.load(f)
# Shirwal tieIn is [73.962, 18.1535]
d2, p2 = min_dist([73.962, 18.1535], nh48)
print(f"Shirwal tie-in to real NH-48: distance={d2:.1f}m, nearest point={p2}")

# 3. Test Gangetic Plains Ramsanehighat tieIn on NH-27
with open('scripts/nh27_gp_ways.json', 'r', encoding='utf-8') as f:
    nh27 = json.load(f)
# Ramsanehighat tieIn is [81.545, 26.815]
d3, p3 = min_dist([81.545, 26.815], nh27)
print(f"Ramsanehighat tie-in to real NH-27: distance={d3:.1f}m, nearest point={p3}")
