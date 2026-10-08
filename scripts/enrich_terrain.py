"""Build offline terrain profiles; GPX geometry is preserved.

Requires output/terrain-altitude/egm96_15.gtx from OSGeo's PROJ archive.
Public Open Topo Data limits: <=100 locations/request, <=1 request/second.
"""
import hashlib
import json
import math
import struct
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
CACHE = ROOT / 'output/terrain-altitude'
CACHE.mkdir(parents=True, exist_ok=True)
GEOID_URL = 'https://download.osgeo.org/proj/vdatum/egm96_15/egm96_15.gtx'
API = 'https://api.opentopodata.org/v1/srtm30m'
grid = (CACHE / 'egm96_15.gtx').read_bytes()
lat0, lon0, dy, dx, rows, cols = struct.unpack('>4d2i', grid[:40])
assert len(grid) == 40 + rows * cols * 4


def geoid(lat, lon):
    y, x = (lat-lat0)/dy, ((lon-lon0) % 360)/dx
    yi, xi = math.floor(y), math.floor(x)
    fy, fx = y-yi, x-xi
    assert 0 <= yi < rows-1
    def value(r, c):
        return struct.unpack_from('>f', grid, 40 + 4*(r*cols+c % cols))[0]
    return ((1-fy)*((1-fx)*value(yi,xi)+fx*value(yi,xi+1))
            + fy*((1-fx)*value(yi+1,xi)+fx*value(yi+1,xi+1)))


def distance(a, b):
    la, lb = math.radians(a[0]), math.radians(b[0])
    h = math.sin((lb-la)/2)**2+math.cos(la)*math.cos(lb)*math.sin(math.radians(b[1]-a[1])/2)**2
    return 12742000*math.asin(math.sqrt(min(1,h)))


def interpolate(a, b, f):
    la, lo, lb, lp = map(math.radians, (*a, *b))
    angle = distance(a,b)/6371000
    wa, wb = math.sin((1-f)*angle)/math.sin(angle), math.sin(f*angle)/math.sin(angle)
    x = wa*math.cos(la)*math.cos(lo)+wb*math.cos(lb)*math.cos(lp)
    y = wa*math.cos(la)*math.sin(lo)+wb*math.cos(lb)*math.sin(lp)
    z = wa*math.sin(la)+wb*math.sin(lb)
    return math.degrees(math.atan2(z,math.hypot(x,y))), math.degrees(math.atan2(y,x))


def fetch(locations):
    query = '|'.join(f'{lat:.8f},{lon:.8f}' for lat,lon in locations)
    key = hashlib.sha256(query.encode()).hexdigest()
    file = CACHE / f'srtm30m-{key}.json'
    if file.exists():
        data = json.loads(file.read_text())
    else:
        url = API + '?' + urllib.parse.urlencode({'locations':query,'interpolation':'bilinear'})
        for attempt in range(3):
            try:
                request = urllib.request.Request(url, headers={'User-Agent':'ProUnbound terrain build'})
                with urllib.request.urlopen(request, timeout=40) as response:
                    data = json.load(response)
                if data.get('status') != 'OK':
                    raise ValueError(data)
                break
            except Exception:
                if attempt == 2: raise
                time.sleep(3)
        file.write_text(json.dumps(data), encoding='utf-8')
        time.sleep(1.1)
    assert data.get('status') == 'OK' and len(data['results']) == len(locations)
    values = []
    for p, r in zip(locations,data['results']):
        assert abs(p[0]-r['location']['lat']) < .000001
        assert abs(p[1]-r['location']['lng']) < .000001
        value = r['elevation']
        if value is None or not math.isfinite(value):
            raise ValueError(f'Missing terrain elevation at {p}; no fabricated fallback')
        values.append(float(value))
    return values


reports = []
for file in [ASSETS/'g30-loop.gpx'] + sorted(ASSETS.glob('sprint-*.gpx')):
    root = ET.parse(file).getroot()
    points = [(float(p.get('lat')),float(p.get('lon')))
              for p in root.iter() if p.tag.split('}')[-1] == 'trkpt']
    points = [p for i,p in enumerate(points) if i == 0 or distance(points[i-1],p) > .001]
    samples, offsets, offset = [points[0]], [0.0], 0.0
    for a,b in zip(points,points[1:]):
        length = distance(a,b)
        steps = max(1,math.ceil(length/100))
        for i in range(1,steps+1):
            samples.append(interpolate(a,b,i/steps))
            offsets.append(offset+length*i/steps)
        offset += length
    print(file.stem, 'samples', len(samples), flush=True)
    heights = []
    for i in range(0,len(samples),100):
        heights.extend(fetch(samples[i:i+100]))
        print('  downloaded',len(heights),'/',len(samples),flush=True)
    # A closed loop has exactly the same terrain altitude at both ends.
    if distance(points[0],points[-1]) < .01:
        heights[-1] = heights[0]
    corrections = [geoid(*p) for p in samples]
    if distance(points[0],points[-1]) < .01:
        corrections[-1] = corrections[0]
    lines = ['# distance_m,msl_egm96_m,ellipsoid_wgs84_m; SRTM30 + EGM96; 2026-10-08']
    for d,h,n in zip(offsets,heights,corrections):
        lines.append(f'{d:.6f},{h:.3f},{h+n:.3f}')
    (ASSETS/f'{file.stem}-altitude.csv').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    reports.append({'id':file.stem,'length_meters':offset,'samples':len(samples),
                    'min_msl_meters':min(heights),'max_msl_meters':max(heights),
                    'min_geoid_meters':min(corrections),'max_geoid_meters':max(corrections),
                    'gpx_sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
    print('  complete',min(heights),'..',max(heights),'m MSL',flush=True)
metadata = {'downloaded':'2026-10-08','dataset':'SRTM 1 arc-second / srtm30m',
            'terrain_api':API,'terrain_datum':'EGM96 (approximately mean sea level)',
            'geoid_url':GEOID_URL,'geoid_sha256':hashlib.sha256(grid).hexdigest(),
            'geoid_interpolation':'bilinear, 15 arc-minute grid',
            'conversion':'WGS84 ellipsoid height = EGM96 terrain height + geoid undulation',
            'max_profile_spacing_meters':100,'routes':reports}
(ASSETS/'terrain-sources.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
