import os
from PIL import Image

img_path = r'C:\Users\HP\.gemini\antigravity\brain\d0131825-87e1-4496-8f00-714272e1e2b1\.user_uploaded\media_1795016897215_0f3164ca.jpg'
if not os.path.exists(img_path):
    print("Not found:", img_path)
    exit(1)

img = Image.open(img_path)
print("Image size:", img.size)
w, h = img.size

# Crop North Karbi Anglong region
crop_nkarbi = img.crop((int(w * 0.1), int(h * 0.1), int(w * 0.9), int(h * 0.5)))
crop_nkarbi.save(r'C:\Users\HP\.gemini\antigravity\brain\d0131825-87e1-4496-8f00-714272e1e2b1\scratch\crop_nkarbi.png')

# Also crop the bottom region (Marat Longri / East Karbi Anglong)
crop_south = img.crop((int(w * 0.1), int(h * 0.5), int(w * 0.9), int(h * 0.95)))
crop_south.save(r'C:\Users\HP\.gemini\antigravity\brain\d0131825-87e1-4496-8f00-714272e1e2b1\scratch\crop_south.png')

print("Cropped successfully!")
