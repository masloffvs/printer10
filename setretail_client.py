"""Small SetRetailX JSON-RPC client shared by UI actions and background tasks."""

import json
from datetime import datetime
from urllib.parse import urlsplit, urlunsplit

import requests

from auth import BASE_URL, URL_RPC


class SetRetailXClient:
    def __init__(self, session, token=None, event_handler=None):
        self.session = session
        self.token = token
        self.event_handler = event_handler

    def _safe_url(self):
        parsed = urlsplit(URL_RPC)
        host = parsed.hostname or ""
        if parsed.port:
            host = f"{host}:{parsed.port}"
        return urlunsplit((parsed.scheme, host, parsed.path, "", ""))

    def _emit(self, title, details, tone="info"):
        if self.event_handler is None:
            return
        try:
            self.event_handler(
                {
                    "timestamp": datetime.now()
                    .astimezone()
                    .strftime("%Y-%m-%d %H:%M:%S %Z"),
                    "title": title,
                    "details": details,
                    "tone": tone,
                }
            )
        except Exception:
            pass

    def _safe_payload(self, payload):
        def redact(value, key=""):
            if any(
                part in key.lower()
                for part in ("token", "password", "authorization", "cookie")
            ):
                return "[скрыто]"
            if isinstance(value, dict):
                return {name: redact(item, str(name)) for name, item in value.items()}
            if isinstance(value, list):
                return [redact(item) for item in value]
            if self.token and isinstance(value, str):
                return value.replace(str(self.token), "[скрыто]")
            return value

        safe = redact(payload)
        if (
            isinstance(payload, dict)
            and isinstance(safe, dict)
            and payload.get("method") == "login"
            and isinstance(safe.get("params"), list)
        ):
            safe["params"][:2] = ["[скрыто]"] * min(2, len(safe["params"]))
        if (
            isinstance(payload, dict)
            and isinstance(safe, dict)
            and payload.get("method") == "getSimpleProductsByFilter"
            and safe.get("params")
        ):
            safe["params"][0] = "[скрыто]"
        return safe

    def _safe_text(self, value, payload=None):
        text = str(value)
        secrets = []
        if self.token:
            secrets.append(str(self.token))
        if isinstance(payload, dict) and payload.get("method") == "login":
            params = payload.get("params")
            if isinstance(params, list):
                secrets.extend(str(item) for item in params[:2])
        if URL_RPC:
            text = text.replace(URL_RPC, self._safe_url())
        for secret in secrets:
            if secret:
                text = text.replace(secret, "[скрыто]")
        return text

    def rpc(self, payload, timeout=15, headers=None):
        if self.session is None:
            raise RuntimeError("Сессия авторизации SetRetailX не создана.")
        if not URL_RPC:
            raise RuntimeError("URL JSON-RPC SetRetailX не настроен.")
        method = payload.get("method", "JSON-RPC")
        safe = self._safe_payload(payload)
        self._emit(
            f"Отправлен запрос: {method}",
            f"URL: {self._safe_url()}\nEndpoint: {payload.get('endpoint', '')}\n\n"
            f"{json.dumps(safe, ensure_ascii=False, indent=2, default=str)}",
        )
        try:
            response = self.session.post(
                URL_RPC, json=payload, timeout=timeout, headers=headers
            )
            try:
                body = response.json()
                if method == "login" and isinstance(body, dict) and "result" in body:
                    body = dict(body)
                    body["result"] = "[скрыто: токен авторизации]"
                formatted = json.dumps(
                    self._safe_payload(body), ensure_ascii=False, indent=2, default=str
                )
            except ValueError:
                formatted = self._safe_text(response.text, payload)[:16000]
            self._emit(
                f"Ответ SetRetailX: HTTP {response.status_code}",
                f"Метод: {method}\nВремя ответа: {response.elapsed.total_seconds():.2f} с\n\n{formatted}",
                "success" if response.ok else "error",
            )
            response.raise_for_status()
            data = response.json()
            if isinstance(data, dict) and data.get("error"):
                raise RuntimeError(
                    f"SetRetailX {method}: {self._safe_text(data['error'], payload)}"
                )
            return data
        except (
            requests.exceptions.RequestException,
            ValueError,
            RuntimeError,
        ) as error:
            message = self._safe_text(error, payload)
            self._emit(
                f"Ошибка обмена: {method}",
                f"{type(error).__name__}: {message}",
                "error",
            )
            error.args = (message,)
            raise

    @staticmethod
    def _unwrap(data, operation):
        if not isinstance(data, dict) or "error" in data:
            raise ValueError(f"Некорректный ответ SetRetailX ({operation}).")
        wrapped = data.get("result")
        if (
            isinstance(wrapped, list)
            and len(wrapped) >= 2
            and isinstance(wrapped[1], list)
        ):
            return wrapped[1]
        if isinstance(wrapped, list):
            return wrapped
        raise ValueError(f"Некорректный ответ SetRetailX ({operation}).")

    def get_nearly_price_changing(self, shelf=12):
        payload = {
            "@class": "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest",
            "id": 953,
            "version": "1.0.0",
            "endpoint": (
                "java:app/SET-PrintPriceTags/GroupingTreeServiceBean!"
                "ru.crystals.setretailx.grouping.api.GroupingTreeService"
            ),
            "method": "getObjectsIds",
            "params": [
                {
                    "shelf": shelf,
                    "@class": "ru.crystals.setretailx.grouping.vo.PrintModeVO",
                },
                {
                    "filters": [
                        {
                            "type": "NearlyPriceChanging",
                            "@class": "ru.crystals.operday.vo.SearchArgumentVO",
                        }
                    ],
                    "tree": [
                        {"@class": "ru.crystals.setretailx.grouping.vo.GroupingNodeVO"}
                    ],
                    "@class": "ru.crystals.setretailx.grouping.vo.GroupingObjectsFilterVO",
                },
            ],
        }
        return [
            str(code)
            for code in self._unwrap(
                self.rpc(
                    payload,
                    headers={"Referer": f"{BASE_URL}/price-tags/not-printed"},
                ),
                "getObjectsIds",
            )
            if code is not None and str(code).strip()
        ]

    def get_products(self, markings, shelf=12):
        markings = list(markings)
        if not markings:
            return []
        payload = {
            "@class": "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest",
            "id": 954,
            "version": "1.0.0",
            "endpoint": (
                "java:app/SET-PrintPriceTags/PriceTagFacadeBean!"
                "ru.crystals.setretailx.printing.common.api.PriceTagFacadeLocal"
            ),
            "method": "getProducts",
            "params": [markings, shelf],
        }
        products = self._unwrap(
            self.rpc(
                payload,
                headers={"Referer": f"{BASE_URL}/price-tags/not-printed"},
            ),
            "getProducts",
        )
        if not all(isinstance(product, dict) for product in products):
            raise ValueError("SetRetailX вернул некорректный список товаров.")
        return products
