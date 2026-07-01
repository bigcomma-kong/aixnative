#!/usr/bin/env python3
"""Pull real credential values from GCP Secret Manager into application-secret.yml.

복구/초기화용: gcloud 인증 후 실행하면 Secret Manager 의 최신 버전을 읽어
로컬 application-secret.yml(gitignored) 을 채운다. 값은 파일에만 쓰고 stdout 엔 이름만.

    export PATH=".../google-cloud-sdk/bin:$PATH"
    python deploy/pull_secrets.py
"""
import os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "application-secret.yml")

# (dotted property, Secret Manager secret name)
PULL = [
    ("security.jwt.secret",         "JWT_SECRET"),
    ("claude.api.key",              "CLAUDE_API_KEY"),
    ("claude.oauth.token",          "CLAUDE_OAUTH_TOKEN"),
    ("mistral.api.key",             "MISTRAL_API_KEY"),
    ("spring.mail.password",        "SPRING_MAIL_PASSWORD"),
    ("toss.secret-key",             "TOSS_SECRET_KEY"),
    ("oauth.google.client-secret",  "GOOGLE_CLIENT_SECRET"),
    ("oauth.kakao.client-secret",   "KAKAO_CLIENT_SECRET"),
    ("oauth.naver.client-secret",   "NAVER_CLIENT_SECRET"),
    ("marketdata.ecos-key",         "ECOS_API_KEY"),
    ("marketdata.reb-key",          "REB_RONE_API_KEY"),
    ("marketdata.data-go-kr-key",   "DATA_GO_KR_API_KEY"),
    ("marketdata.vworld-key",       "VWORLD_API_KEY"),
    ("marketdata.juso-key",         "JUSO_API_KEY"),
    ("marketfeed.ingest-token",     "MARKETFEED_INGEST_TOKEN"),
]

def fetch(secret):
    try:
        out = subprocess.run(
            ["gcloud", "secrets", "versions", "access", "latest", "--secret", secret],
            capture_output=True, text=True, timeout=30)
        if out.returncode == 0:
            return out.stdout  # exact bytes, no trailing strip (may matter for keys)
    except Exception:
        pass
    return None

def yq(v):
    return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'

def nest(values):
    """values: dict dotted->value(str or None). Build ordered nested lines."""
    tree = {}
    for dotted, _ in PULL:
        cur = tree
        parts = dotted.split(".")
        for p in parts[:-1]:
            cur = cur.setdefault(p, {})
        cur[parts[-1]] = values.get(dotted)
    lines = []
    def emit(d, indent):
        for k, v in d.items():
            if isinstance(v, dict):
                lines.append("  " * indent + f"{k}:")
                emit(v, indent + 1)
            else:
                if v is None or v == "":
                    lines.append("  " * indent + f"{k}: \"\"")
                else:
                    lines.append("  " * indent + f"{k}: {yq(v)}")
    emit(tree, 0)
    return "\n".join(lines)

HEADER = """# ─────────────────────────────────────────────────────────────────────────────
# application-secret.yml — 크리덴셜 전용 단일 소스 (GITIGNORED)
#  • deploy/pull_secrets.py 로 GCP Secret Manager 에서 채움. 수동 편집도 가능.
#  • 기본 프로필 secret,h2 부팅 시 자동 로드. 배포는 deploy.sh 가 이 파일을 Secret Manager 로 push.
#  • ⚠ 커밋 안 되는 원본 → 반드시 백업(비번매니저/sops). docs/ENV.md 참고.
# ─────────────────────────────────────────────────────────────────────────────
"""

def main():
    values, filled, empty = {}, [], []
    for dotted, secret in PULL:
        v = fetch(secret)
        if v:
            values[dotted] = v
            filled.append(secret)
        else:
            empty.append(secret)
    body = nest(values)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(HEADER + "\n" + body + "\n")
    print(f"채움({len(filled)}): {', '.join(filled)}")
    if empty:
        print(f"비어있음({len(empty)}, Secret Manager 에 없음 → 수동 입력): {', '.join(empty)}")

if __name__ == "__main__":
    main()
