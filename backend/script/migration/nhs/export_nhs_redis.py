#!/usr/bin/env python3
"""Export durable Nhs Redis conversation and memory records to hashed JSONL."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any, Iterator

import redis


MAX_RECORD_BYTES = 8 * 1024 * 1024


def canonical_bytes(value: dict[str, Any]) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def record(payload: dict[str, Any]) -> dict[str, Any]:
    result = dict(payload)
    result["recordHash"] = hashlib.sha256(canonical_bytes(payload)).hexdigest()
    return result


def text(value: bytes | str) -> str:
    return value.decode("utf-8") if isinstance(value, bytes) else str(value)


def split_conversation_key(key: str) -> tuple[str, str] | None:
    prefix = "conversation:"
    suffix = ":history"
    if not key.startswith(prefix) or not key.endswith(suffix):
        return None
    body = key[len(prefix) : -len(suffix)]
    if ":" not in body:
        return None
    user_id, conversation_id = body.split(":", 1)
    if not user_id or not conversation_id:
        return None
    return user_id, conversation_id


def split_summary_key(key: str) -> tuple[str, str] | None:
    prefix = "memory:summary:"
    if not key.startswith(prefix):
        return None
    body = key[len(prefix) :]
    if ":" not in body:
        return None
    user_id, conversation_id = body.split(":", 1)
    return (user_id, conversation_id) if user_id and conversation_id else None


def scan(client: redis.Redis, pattern: str, count: int) -> Iterator[str]:
    for key in client.scan_iter(match=pattern, count=count):
        yield text(key)


def export_records(client: redis.Redis, scan_count: int) -> Iterator[dict[str, Any]]:
    for key in scan(client, "conversation:*:*:history", scan_count):
        identity = split_conversation_key(key)
        if identity is None:
            continue
        user_id, conversation_id = identity
        messages: list[dict[str, Any]] = []
        for raw in client.lrange(key, 0, -1):
            parsed = json.loads(text(raw))
            if not isinstance(parsed, dict):
                raise ValueError(f"conversation item is not an object: {key}")
            messages.append(parsed)
        yield record(
            {
                "kind": "conversation",
                "userId": user_id,
                "conversationId": conversation_id,
                "ttl": client.ttl(key),
                "messages": messages,
            }
        )

    for key in scan(client, "nhs:agent:ltm:*", scan_count):
        user_id = key.removeprefix("nhs:agent:ltm:")
        if not user_id:
            continue
        values = {text(field): text(value) for field, value in client.hgetall(key).items()}
        yield record(
            {
                "kind": "ltm",
                "userId": user_id,
                "ttl": client.ttl(key),
                "values": values,
            }
        )

    for key in scan(client, "memory:summary:*", scan_count):
        identity = split_summary_key(key)
        if identity is None:
            continue
        user_id, conversation_id = identity
        values = {text(field): text(value) for field, value in client.hgetall(key).items()}
        values.pop("embedding", None)
        values.pop("_embedding_vec", None)
        yield record(
            {
                "kind": "summary",
                "userId": user_id,
                "conversationId": conversation_id,
                "ttl": client.ttl(key),
                "values": values,
            }
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scan-count", type=int, default=500)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    redis_url = os.environ.get("NHS_REDIS_URL")
    if not redis_url:
        raise SystemExit("NHS_REDIS_URL is required")
    if args.scan_count < 10 or args.scan_count > 10_000:
        raise SystemExit("--scan-count must be between 10 and 10000")
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    client = redis.Redis.from_url(
        redis_url,
        decode_responses=False,
        socket_connect_timeout=5,
        socket_timeout=30,
    )
    client.ping()
    count = 0
    with temporary.open("xb") as stream:
        os.chmod(temporary, 0o600)
        for item in export_records(client, args.scan_count):
            line = canonical_bytes(item)
            if len(line) > MAX_RECORD_BYTES:
                raise ValueError(f"export record exceeds 8 MiB: {item.get('kind')}")
            stream.write(line + b"\n")
            count += 1
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, output)
    os.chmod(output, 0o600)
    print(json.dumps({"output": str(output), "records": count}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
