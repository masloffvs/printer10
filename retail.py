import re
import sqlite3
import time

import requests

from auth import BASE_URL, URL_RPC
from config import DB_PATH, PRINT_FORMATS

active_session = None
session_token = None


def extract_barcode(user_input):
    """Извлекает barcode или de из строки сканера, если они там есть."""
    text = str(user_input).strip()

    match = re.search(r"(?:barcode|de)=(\d+)", text, re.IGNORECASE)
    if match:
        return match.group(1)

    return text


def normalize_format(value):
    """Приводит латинские и кириллические обозначения к А, Б или Ц."""
    return FORMAT_ALIASES.get(str(value).strip().upper())


def search_offline(barcode):
    """
    Ищет точное совпадение в SQLite.
    Возвращает словарь товара или None.
    Ошибка базы передаётся вызывающему коду.
    """
    conn = sqlite3.connect(str(DB_PATH))

    try:
        cursor = conn.cursor()
        cursor.execute(
            """
            SELECT product_id, name, vendor_code, barcode
            FROM products
            WHERE CAST(barcode AS TEXT) = ?
            LIMIT 1
            """,
            (str(barcode),),
        )
        row = cursor.fetchone()

        if row is None:
            return None

        product_id, name, vendor_code, found_barcode = row

        return {
            "st_code": str(product_id),
            "name": name or "Без названия",
            "vendor_code": vendor_code or "",
            "barcode": str(found_barcode or barcode),
            "source": "offline",
        }
    finally:
        conn.close()


def online_rpc(payload, timeout=15):
    """Отправляет JSON-RPC запрос в SetRetailX."""
    if active_session is None:
        raise RuntimeError("Сессия авторизации не создана.")

    response = active_session.post(URL_RPC, json=payload, timeout=timeout)
    response.raise_for_status()
    return response.json()


def search_online(barcode):
    """Ищет товар на сервере. Используется только при отсутствии в SQLite."""
    active_session.headers["Referer"] = f"{BASE_URL}/price-tags/custom-printing"

    search_payload = {
        "@class": "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest",
        "id": 73,
        "version": "1.0.0",
        "endpoint": (
            "java:app/SET-Products/SET/ProductsFinder!"
            "ru.crystals.setretailx.products.ProductManagerFinderLocal"
        ),
        "method": "getSimpleProductsByFilter",
        "params": [
            session_token,
            {
                "good": f"use_like:%{barcode}%",
                "productStatusCode": 99,
                "@class": "ru.crystals.setretailx.products.common.ProductsFilter",
            },
            0,
            20,
        ],
    }

    data = online_rpc(search_payload)
    result_wrapper = data.get("result", [])

    if not isinstance(result_wrapper, list) or len(result_wrapper) < 2:
        raise ValueError(f"Некорректный ответ сервера: {data}")

    products = result_wrapper[1]
    if not products:
        return None

    # Сначала предпочитаем точное совпадение штрихкода, если сервер его вернул.
    selected = products[0]
    barcode_text = str(barcode)

    for product in products:
        product_barcode = str(product.get("barcode") or product.get("barCode") or "")
        if product_barcode == barcode_text:
            selected = product
            break

    st_code = selected.get("identifier")
    if not st_code:
        raise ValueError(
            f"Товар найден онлайн, но сервер не вернул identifier для {barcode}."
        )

    return {
        "st_code": str(st_code),
        "name": selected.get("name") or "Без названия",
        "vendor_code": selected.get("vendorCode") or "",
        "barcode": str(selected.get("barcode") or barcode),
        "source": "online",
    }


def lookup_product(barcode):
    """
    Сначала ищет в SQLite.
    Онлайн-поиск выполняется только если локальный поиск не нашёл товар.
    """
    offline_product = search_offline(barcode)
    if offline_product:
        return offline_product

    return search_online(barcode)


def print_group(format_key, amount_to_print, entries):
    """Отправляет одну группу товаров на печать."""
    format_info = PRINT_FORMATS[format_key]
    basket = [entry["st_code"] for entry in entries]

    if not basket:
        return False

    active_session.headers["Referer"] = (
        f"{BASE_URL}/price-tags/custom-printing/printing"
    )

    print_payload = {
        "@class": "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest",
        "id": 100,
        "version": "1.0.0",
        "endpoint": (
            "java:app/SET-PrintPriceTags/GroupingPrintServiceBean!"
            "ru.crystals.setretailx.grouping.api.GroupingPrintService"
        ),
        "method": "print",
        "params": [
            {
                "shelf": 99,
                "filters": [],
                "nodes": [
                    {"@class": ("ru.crystals.setretailx.grouping.vo.GroupingNodeVO")}
                ],
                "basket": basket,
                "isDefault": False,
                "isFormat": False,
                "substitutions": {},
                "params": [
                    {
                        "templateId": format_info["template_id"],
                        "amountToPrint": amount_to_print,
                        "printerId": format_info["printer_id"],
                        "@class": ("ru.crystals.setretailx.printers.PrintingParamsVO"),
                    }
                ],
                "date": int(time.time() * 1000),
                "numberOfCopies": None,
                "isSeparateContext": False,
                "@class": ("ru.crystals.setretailx.grouping.vo.PrintRequestVO"),
            }
        ],
    }

    print(
        f"\n[Печать] {format_info['name']}, "
        f"ценников на товар: {amount_to_print}, товаров: {len(basket)}"
    )

    try:
        data = online_rpc(print_payload)
        result_wrapper = data.get("result", [])

        if not isinstance(result_wrapper, list) or len(result_wrapper) < 2:
            print(f"[Ошибка] Некорректный ответ при создании задания: {data}")
            return False

        uuids = result_wrapper[1]
        if not isinstance(uuids, list) or not uuids:
            print("[Ошибка] Сервер не вернул UUID задания.")
            return False

        task_uuid = uuids[0]
        print(f"[Принтер] Задание зарегистрировано: {task_uuid}")

        active_session.headers["Referer"] = f"{BASE_URL}/price-tags/custom-printing"

        confirm_payload = {
            "@class": "ru.crystals.commons.jsonrpc.domain.JsonRpcRequest",
            "id": 63,
            "version": "1.0.0",
            "endpoint": (
                "java:app/SET-PrintPriceTags/PrintingBoxManagerBean!"
                "ru.crystals.setretailx.printing.protocol.manager."
                "PrintingBoxManager"
            ),
            "method": "execute",
            "params": [task_uuid, "START"],
        }

        confirm_data = online_rpc(confirm_payload, timeout=15)
        error = confirm_data.get("error")

        if error:
            print(f"[Ошибка] Сервер не подтвердил запуск печати: {error}")
            return False

        print("[Успех] Печать запущена.")
        return True

    except requests.exceptions.RequestException as error:
        print(f"[Ошибка сети при печати] {error}")
        return False
    except (ValueError, RuntimeError) as error:
        print(f"[Ошибка печати] {error}")
        return False
