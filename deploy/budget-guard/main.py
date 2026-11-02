"""예산 초과 시 Cloud SQL 자동 중지 (Cloud Functions gen2, Pub/Sub 트리거).

Billing 예산 알림(Pub/Sub)은 하루 여러 번 온다. 이번 달 실제 비용(costAmount)이
예산(budgetAmount) 이상이면 Cloud SQL 인스턴스를 중지(activationPolicy=NEVER)한다.
데이터는 보존되며, 콘솔 SQL > 인스턴스 > 시작 으로 다시 켠다.
"""
import base64
import json
import logging
import os

import functions_framework
import google.auth
from google.auth.transport.requests import AuthorizedSession

logging.getLogger().setLevel(logging.INFO)

PROJECT_ID = os.environ.get("TARGET_PROJECT", "aixnative")
INSTANCE = os.environ.get("TARGET_SQL_INSTANCE", "aixnative")
SQLADMIN_URL = f"https://sqladmin.googleapis.com/v1/projects/{PROJECT_ID}/instances/{INSTANCE}"


def _session() -> AuthorizedSession:
    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
    return AuthorizedSession(creds)


def _parse(cloud_event) -> dict:
    raw = cloud_event.data["message"]["data"]
    return json.loads(base64.b64decode(raw).decode("utf-8"))


@functions_framework.cloud_event
def on_budget(cloud_event):
    try:
        payload = _parse(cloud_event)
    except (KeyError, ValueError) as e:
        logging.error("unparseable budget message - ignored: %s", e)
        return
    cost = float(payload.get("costAmount", 0))
    budget = float(payload.get("budgetAmount", 0))
    logging.info("budget check cost=%s budget=%s", cost, budget)

    if budget <= 0 or cost < budget:
        return

    session = _session()
    current = session.get(SQLADMIN_URL, timeout=30)
    current.raise_for_status()
    policy = current.json().get("settings", {}).get("activationPolicy")
    if policy == "NEVER":
        logging.info("already stopped - skip")
        return

    resp = session.patch(SQLADMIN_URL, json={"settings": {"activationPolicy": "NEVER"}}, timeout=30)
    resp.raise_for_status()
    logging.warning("BUDGET EXCEEDED cost=%s budget=%s -> stopped Cloud SQL %s", cost, budget, INSTANCE)
