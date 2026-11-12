"""SetRetailX authentication configured through environment variables."""

import base64
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

import requests
from dotenv import load_dotenv


if getattr(sys, "frozen", False):
    ENV_PATH = Path(sys.executable).resolve().with_name(".env")
else:
    ENV_PATH = Path(__file__).resolve().with_name(".env")

# Environment variables already provided by the OS take precedence over .env.
load_dotenv(ENV_PATH)

exchange_event_handler = None


def _emit_exchange_event(title, details, tone="info"):
    if exchange_event_handler is None:
        return
    event = {
        "timestamp": datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %Z"),
        "title": title,
        "details": details,
        "tone": tone,
    }
    try:
        exchange_event_handler(event)
    except Exception:
        # Logging must not interfere with authentication.
        pass


URL_RPC = os.getenv("SETRETAILX_RPC_URL", "").strip()
_parsed_rpc_url = urlparse(URL_RPC)
HOST_DOMAIN = _parsed_rpc_url.hostname or ""
BASE_URL = f"{_parsed_rpc_url.scheme}://{_parsed_rpc_url.netloc}" if HOST_DOMAIN else ""

BASE_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0"
    ),
    "Accept": "application/json, text/plain, */*",
    "Accept-Encoding": "gzip, deflate",
    "Accept-Language": "ru,en;q=0.9,en-GB;q=0.8,en-US;q=0.7",
    "DNT": "1",
    "Content-Type": "application/json;charset=UTF-8",
}


def get_authenticated_session(username=None, password=None):
    """Authenticate and return ``(requests.Session, raw_token)``."""
    username = (
        username if username is not None else os.getenv("SETRETAILX_USERNAME", "")
    )
    password = (
        password if password is not None else os.getenv("SETRETAILX_PASSWORD", "")
    )

    missing = []
    if (
        not URL_RPC
        or _parsed_rpc_url.scheme not in ("http", "https")
        or not HOST_DOMAIN
    ):
        missing.append("SETRETAILX_RPC_URL")
    if not username:
        missing.append("SETRETAILX_USERNAME")
    if not password:
        missing.append("SETRETAILX_PASSWORD")
    if missing:
        raise ValueError(
            "Задайте параметры подключения в окружении или .env: " + ", ".join(missing)
        )

    session = requests.Session()
    session.headers.update(BASE_HEADERS)
    session.headers["Origin"] = BASE_URL
    session.headers["Referer"] = f"{BASE_URL}/login?redirect=/products/search"

    # The backend expects Basic auth for login. Build it from the environment
    # credentials instead of storing a second, hard-coded copy of the secret.
    credentials = base64.b64encode(f"{username}:{password}".encode("utf-8"))
    session.headers["Authorization"] = f"Basic {credentials.decode('ascii')}"

    login_payload = {
        "@class": "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest",
        "id": 27,
        "version": "1.0.0",
        "endpoint": (
            "java:app/SET-Users/SessionManagerBean!"
            "ru.crystals.setretailx.users.ejb.interfaces.ISessionManagerLocal"
        ),
        "method": "login",
        "params": [username, password, "MAIN"],
    }

    print("[Auth] Отправка запроса на авторизацию...")
    try:
        # Import lazily to avoid a module cycle: the client reads URL_RPC and
        # BASE_URL from this module.
        from setretail_client import SetRetailXClient

        data = SetRetailXClient(session, event_handler=_emit_exchange_event).rpc(
            login_payload, timeout=10
        )
        if isinstance(data, dict) and "JsonRpcSuccessResponse" in data.get(
            "@class", ""
        ):
            raw_token = data.get("result")
            if raw_token:
                session.cookies.set(
                    "JSESSIONID",
                    f"{raw_token}.{HOST_DOMAIN}",
                    domain=HOST_DOMAIN,
                    path="/",
                )
                session.headers.pop("Authorization", None)
                print("[Auth] Успешный вход!")
                return session, raw_token

        raise ValueError("Сервер отклонил авторизацию или вернул неожиданный ответ.")
    except Exception as error:
        print(f"[Auth] Ошибка при авторизации: {error}")
        raise
