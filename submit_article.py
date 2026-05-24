import requests
import json
import os

try:
    with open('ARTICLE.md', 'r') as f:
        lines = f.readlines()
        content = "".join(lines)
        title = next((line.strip('# ').strip() for line in lines if line.startswith('#')), "Untitled")
        preview = lines[1].strip() if len(lines) > 1 else title

    with open('secret.txt', 'r') as f:
        secret = f.read().strip()

    payload = {
        "title": title,
        "content": content,
        "username": "joshua",
        "preview": preview,
        "secret": secret
    }

    url = "https://staging--cwgpwvt57d765i88v5yk.youbase.cloud/api/public/submit-article"
    response = requests.post(url, json=payload)
    
    print(f"Status Code: {response.status_code}")
    try:
        print(response.json())
    except:
        print(response.text)

except Exception as e:
    print(f"Error: {e}")
