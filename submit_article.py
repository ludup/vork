import requests
import json
import os
import re

try:
    with open('ARTICLE.md', 'r') as f:
        lines = f.readlines()
        content = "".join(lines)
        title = next((line.strip('# ').strip() for line in lines if line.startswith('#')), "Untitled")
        preview = lines[1].strip() if len(lines) > 1 else title

    with open('secret.txt', 'r') as f:
        secret = f.read().strip()

    # Find the first SVG image reference in the article and load its raw content.
    # Supports paths like /images/foo.svg or src/main/resources/static/images/foo.svg.
    svg_content = None
    svg_match = re.search(r'!\[.*?\]\(([^)]*\.svg)\)', content)
    if svg_match:
        img_path = svg_match.group(1)
        # Strip any URL prefix pointing at the staging server
        img_path = re.sub(r'^https?://[^/]+', '', img_path)
        # Map /images/... to the static resource directory
        if img_path.startswith('/images/'):
            img_path = 'src/main/resources/static' + img_path
        if os.path.isfile(img_path):
            with open(img_path, 'r') as f:
                svg_content = f.read()
            print(f"Loaded banner SVG: {img_path}")
        else:
            print(f"Warning: SVG file not found at resolved path: {img_path}")

    payload = {
        "title": title,
        "content": content,
        "username": "joshua",
        "preview": preview,
        "secret": secret
    }
    if svg_content:
        payload["svg"] = svg_content

    url = "https://staging--cwgpwvt57d765i88v5yk.youbase.cloud/api/public/submit-article"
    response = requests.post(url, json=payload)
    
    print(f"Status Code: {response.status_code}")
    try:
        print(response.json())
    except:
        print(response.text)

except Exception as e:
    print(f"Error: {e}")
