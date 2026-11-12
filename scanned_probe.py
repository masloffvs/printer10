"""Normalized payload emitted by the shared scanner input."""

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import re
from urllib.parse import parse_qsl


@dataclass(frozen=True)
class ScannedProbe:
    """One scanner event; every source field is optional and kept as text."""

    barcode: str = ""
    price1: str = ""
    price2: str = ""
    price3: str = ""
    price4: str = ""
    stcode: str = ""
    russian_mark: str = ""

    @classmethod
    def from_text(cls, value):
        text = str(value or "").strip()
        if re.fullmatch(r"\d+", text):
            return cls(barcode=text)
        text = re.sub(r"\s*\n\s*|\s*\r\s*", "&", text)
        first_field = re.search(
            r"(?:barcode|price[1-4]|stcode|russian_mark)=", text, re.IGNORECASE
        )
        if first_field:
            text = text[first_field.start() :]

        fields = {name: "" for name in cls.__dataclass_fields__}
        for name, field_value in parse_qsl(text, keep_blank_values=True):
            normalized_name = name.strip().lower()
            if normalized_name in fields:
                fields[normalized_name] = field_value.strip()
        if not any(fields.values()) and text and "=" not in text and "&" not in text:
            fields["barcode"] = text
        return cls(**fields)

    @property
    def has_price_data(self):
        return any(getattr(self, f"price{number}") for number in (1, 2, 3, 4))

    @property
    def has_data(self):
        return any(getattr(self, name) for name in self.__dataclass_fields__)

    def prices_as_decimals(self):
        prices = {}
        for number in ("1", "2", "3", "4"):
            raw = getattr(self, f"price{number}")
            if not raw:
                continue
            try:
                price = Decimal(raw.replace(",", "."))
            except (InvalidOperation, ValueError):
                raise ValueError(f"Некорректная цена price{number}: {raw}")
            if not price.is_finite() or price < 0:
                raise ValueError(f"Некорректная цена price{number}: {raw}")
            prices[number] = price
        return prices
