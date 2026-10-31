#!/usr/bin/env python3
"""Export stopped-write legacy BootFS metadata and search originals; standard library only."""
import argparse
import base64
import json
import os
import urllib.request
import urllib.parse


def request(url, body=None, key=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=60) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--etcd", required=True, help="legacy etcd HTTP endpoint")
    parser.add_argument("--meili", required=True, help="legacy MeiliSearch HTTP endpoint")
    parser.add_argument("--output", required=True)
    parser.add_argument("--writes-stopped", action="store_true", required=True,
                        help="confirm writers on every old node have been stopped")
    args = parser.parse_args()
    metadata = {}
    for prefix in ("/files/", "/s3/", "/properties/"):
        encoded = lambda value: base64.b64encode(value.encode()).decode()
        # These ASCII namespaces have an exact exclusive prefix end.
        end = prefix[:-1] + chr(ord(prefix[-1]) + 1)
        response = request(args.etcd.rstrip("/") + "/v3/kv/range", {"key": encoded(prefix), "range_end": encoded(end)})
        for item in response.get("kvs", []):
            metadata[base64.b64decode(item["key"]).decode()] = base64.b64decode(item.get("value", "")).decode()
    key = os.environ.get("MEILISEARCH_MASTER_KEY")
    index_list = request(args.meili.rstrip("/") + "/indexes?limit=10000", key=key)
    if index_list.get("total", 0) > len(index_list.get("results", [])):
        raise RuntimeError("Too many legacy indexes; export requires pagination support")
    documents = {}
    for index in index_list.get("results", []):
        uid = index["uid"]
        rows = []
        while True:
            page = request(args.meili.rstrip("/") + "/indexes/" + urllib.parse.quote(uid, safe="") +
                           "/documents?limit=500&offset=" + str(len(rows)), key=key)
            batch = page.get("results", [])
            rows.extend(batch)
            if not batch or len(rows) >= page.get("total", len(rows)):
                break
        documents[uid] = rows
    # Exclusive create prevents overwriting a previous backup. Keys are never printed.
    with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as output:
        json.dump({"formatVersion": 1, "metadata": metadata, "documents": documents}, output, ensure_ascii=False)
    print("Exported", len(metadata), "metadata records and", sum(map(len, documents.values())), "source documents.")


if __name__ == "__main__":
    main()
