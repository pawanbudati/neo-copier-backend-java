import sys
import os
import json
import re
import io
import zipfile
import gzip
from datetime import datetime, date, timedelta
from pathlib import Path
import httpx
import psycopg

# Load .env if present
def load_env():
    env_file = Path(__file__).parent.parent / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, val = line.split("=", 1)
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))

load_env()

DATABASE_URL = os.environ.get("DATABASE_URL", "postgresql://postgres:postgres@localhost:5432/neo_copier")
# Normalize Supabase/PG URL for psycopg3
if DATABASE_URL.startswith("postgresql://") or DATABASE_URL.startswith("postgres://"):
    pass

SCRIP_CATEGORIES = [
    {"key": "nse_fo", "label": "NSE Futures & Options", "exchange": "NFO", "segment": "F&O"},
    {"key": "bse_fo", "label": "BSE Futures & Options", "exchange": "BFO", "segment": "F&O"},
    {"key": "nse_cm", "label": "NSE Equity (Cash)", "exchange": "NSE", "segment": "EQUITY"},
    {"key": "bse_cm", "label": "BSE Equity (Cash)", "exchange": "BSE", "segment": "EQUITY"},
    {"key": "mcx_fo", "label": "MCX Commodities", "exchange": "MCX", "segment": "COMMODITY"},
    {"key": "cde_fo", "label": "CDS Currency Derivatives", "exchange": "CDE", "segment": "CURRENCY"},
]

SCRIP_REF_EXPIRY_PATTERN = re.compile(r"(\d{2})([A-Z]{3})(\d{2})")
MONTH_MAP = {
    "JAN": 1, "FEB": 2, "MAR": 3, "APR": 4, "MAY": 5, "JUN": 6,
    "JUL": 7, "AUG": 8, "SEP": 9, "OCT": 10, "NOV": 11, "DEC": 12
}

def map_exchange_segment(raw: str) -> str:
    val = (raw or "").upper()
    if "NSE_FO" in val or val == "NFO": return "NFO"
    if "BSE_FO" in val or val == "BFO": return "BFO"
    if "NSE_CM" in val or val == "NSE": return "NSE"
    if "BSE_CM" in val or val == "BSE": return "BSE"
    if "MCX" in val: return "MCX"
    if "CDE" in val or "CDS" in val: return "CDE"
    return raw or "NSE"

def derive_segment(symbol: str, exchange_raw: str, instrument_name: str, option_type: str) -> str:
    combined = f"{symbol} {exchange_raw} {instrument_name} {option_type}".upper()
    if "CE" in combined or "CALL" in combined: return "CE"
    if "PE" in combined or "PUT" in combined: return "PE"
    if "FUT" in combined: return "FUT"
    return "EQ"

def parse_expiry_date(scrip_ref: str, raw_expiry: str) -> str | None:
    if scrip_ref:
        m = SCRIP_REF_EXPIRY_PATTERN.search(scrip_ref.upper())
        if m:
            day, month_str, year = m.groups()
            month = MONTH_MAP.get(month_str)
            if month:
                try:
                    return f"20{year}-{month:02d}-{int(day):02d}"
                except Exception:
                    pass
    if raw_expiry:
        try:
            if len(raw_expiry) >= 10 and raw_expiry[4] == '-' and raw_expiry[7] == '-':
                return raw_expiry[:10]
            if raw_expiry.isdigit():
                stamp = int(raw_expiry) / (1000 if len(raw_expiry) == 13 else 1)
                return datetime.fromtimestamp(stamp).date().isoformat()
        except Exception:
            pass
    return None

def download_bytes(url: str) -> bytes:
    headers = {"User-Agent": "Mozilla/5.0", "Accept-Encoding": "gzip, deflate"}
    sys.stderr.write(f"[ScripLoader] Downloading {url}...\n")
    sys.stderr.flush()
    with httpx.Client(timeout=15.0, follow_redirects=True) as client:
        resp = client.get(url, headers=headers)
        resp.raise_for_status()
        raw_bytes = resp.content

    sys.stderr.write(f"[ScripLoader] Downloaded {len(raw_bytes)} bytes from {url}\n")
    sys.stderr.flush()

    if len(raw_bytes) > 4 and raw_bytes[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(raw_bytes)) as zf:
            names = zf.namelist()
            if names:
                return zf.read(names[0])
    if len(raw_bytes) > 2 and raw_bytes[0] == 0x1F and raw_bytes[1] == 0x8B:
        with gzip.GzipFile(fileobj=io.BytesIO(raw_bytes)) as gzf:
            return gzf.read()
    return raw_bytes

def parse_csv_scrips(csv_bytes: bytes, filter_fn=None) -> list[tuple]:
    text = csv_bytes.decode("utf-8", errors="replace").replace("\ufeff", "")
    lines = text.splitlines()
    if not lines:
        return []

    headers = [h.strip().lower() for h in lines[0].split(",")]
    
    # Helper to find column index
    def find_idx(candidates):
        for idx, h in enumerate(headers):
            for c in candidates:
                if h == c:
                    return idx
        return -1

    token_idx = find_idx(["psymbol", "ptoken", "token", "scriptoken", "script_token", "scripttoken", "tokenid", "instrument_token"])
    symbol_idx = find_idx(["ptrdsymbol", "trdsymbol", "tradingsymbol", "trading_symbol", "symbol", "symbolname"])
    ref_idx = find_idx(["pscriprefkey", "scriprefkey", "scrip_ref_key"])
    inst_idx = find_idx(["psymname", "symname", "symbol_name", "pinstname", "instrument_name", "instrumentname", "instname"])
    exch_idx = find_idx(["pexchseg", "exchseg", "exchange_segment", "pexchange", "exchange", "segment", "segmentname"])
    opt_idx = find_idx(["poptiontype", "optiontype", "option_type", "opt_type"])
    strike_idx = find_idx(["pstrikeprice", "strikeprice", "strike_price", "strike", "dstrikeprice"])
    lot_idx = find_idx(["ilotsize", "llotsize", "iboardlotqty", "plotsize", "lotsize", "boardlotqty"])
    exp_idx = find_idx(["pexpirydate", "expirydate", "expiry_date", "expiry", "exp_date"])

    parsed_map = {}

    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split(",")
        n_parts = len(parts)

        token = parts[token_idx].strip() if 0 <= token_idx < n_parts else ""
        symbol = parts[symbol_idx].strip() if 0 <= symbol_idx < n_parts else ""
        if not token or not symbol:
            continue

        ref_key = parts[ref_idx].strip() if 0 <= ref_idx < n_parts else ""
        inst_name = parts[inst_idx].strip() if 0 <= inst_idx < n_parts else ""
        if not inst_name: inst_name = symbol
        exch_raw = parts[exch_idx].strip() if 0 <= exch_idx < n_parts else ""
        opt_type = parts[opt_idx].strip() if 0 <= opt_idx < n_parts else ""
        strike_raw = parts[strike_idx].strip() if 0 <= strike_idx < n_parts else ""
        lot_raw = parts[lot_idx].strip() if 0 <= lot_idx < n_parts else ""
        exp_raw = parts[exp_idx].strip() if 0 <= exp_idx < n_parts else ""

        try:
            strike = float(strike_raw) if strike_raw else 0.0
            if strike > 0: strike = strike / 100.0
        except ValueError:
            strike = 0.0

        try:
            lot_size = int(float(lot_raw)) if lot_raw else 1
            if lot_size < 1: lot_size = 1
        except ValueError:
            lot_size = 1

        exchange = map_exchange_segment(exch_raw)
        segment = derive_segment(symbol, exch_raw, inst_name, opt_type)
        expiry = parse_expiry_date(ref_key, exp_raw)

        if filter_fn:
            if not filter_fn(symbol, inst_name, segment, expiry):
                continue

        # Map by token to eliminate internal CSV duplicate scripttokens!
        parsed_map[token] = (token, symbol, ref_key, inst_name, exchange, segment, strike, expiry, lot_size)

    return list(parsed_map.values())

def get_candidate_urls(cat_key: str, active_account: dict = None) -> list[str]:
    cat_key_lower = cat_key.lower()
    urls = []
    if active_account and active_account.get("accessToken"):
        try:
            api_base = os.environ.get("KOTAK_API_BASE", "https://mis.kotaksecurities.com")
            headers = {
                "Authorization": active_account.get("accessToken", ""),
                "neo-fin-key": "neotradeapi",
                "Sid": active_account.get("sid", ""),
                "Auth": active_account.get("neoToken", ""),
                "User-Agent": "Mozilla/5.0"
            }
            res = httpx.get(f"{api_base}/v1/scrip-master/urls", headers=headers, timeout=10.0)
            if res.status_code == 200:
                data = res.json()
                def extract(obj):
                    if isinstance(obj, str) and (cat_key_lower in obj.lower() or ".csv" in obj.lower()):
                        if cat_key_lower in obj.lower(): urls.append(obj)
                    elif isinstance(obj, list):
                        for item in obj: extract(item)
                    elif isinstance(obj, dict):
                        for v in obj.values(): extract(v)
                extract(data)
        except Exception:
            pass

    # Fallbacks
    dates = [date.today().isoformat(), (date.today() - timedelta(days=1)).isoformat(), (date.today() - timedelta(days=2)).isoformat()]
    for date_str in dates:
        if cat_key_lower == "nse_cm":
            urls.append(f"https://lapi.kotaksecurities.com/wso2-scripmaster/v1/prod/{date_str}/transformed-v1/nse_cm-v1.csv")
            urls.append(f"https://lapi.kotaksecurities.com/wso2-scripmaster/v1/prod/{date_str}/transformed/nse_cm.csv")
        elif cat_key_lower == "bse_cm":
            urls.append(f"https://lapi.kotaksecurities.com/wso2-scripmaster/v1/prod/{date_str}/transformed-v1/bse_cm-v1.csv")
            urls.append(f"https://lapi.kotaksecurities.com/wso2-scripmaster/v1/prod/{date_str}/transformed/bse_cm.csv")
        else:
            urls.append(f"https://lapi.kotaksecurities.com/wso2-scripmaster/v1/prod/{date_str}/transformed/{cat_key_lower}.csv")
            urls.append(f"https://lapi.kotaksecurities.com/wso2-scripmaster/v1/prod/{date_str}/transformed-v1/{cat_key_lower}-v1.csv")
            urls.append(f"https://wsm.kotaksecurities.com/scrip-master/{cat_key_lower}_{date_str}.csv")
    
    seen = set()
    res = []
    for u in urls:
        if u not in seen:
            seen.add(u)
            res.append(u)
    return res

def save_scrips_to_postgres(scrips_tuples: list[tuple], exchange_to_clear: str = None):
    if not scrips_tuples and not exchange_to_clear:
        return 0

    sys.stderr.write(f"[ScripLoader] Saving {len(scrips_tuples)} scrips to PostgreSQL...\n")
    sys.stderr.flush()

    with psycopg.connect(DATABASE_URL, prepare_threshold=None) as conn:
        with conn.cursor() as cur:
            if exchange_to_clear:
                cur.execute("DELETE FROM scrips WHERE exchange = %s", (exchange_to_clear,))
                conn.commit()

            if scrips_tuples:
                batch_size = 500
                sql_prefix = "INSERT INTO scrips (scripttoken, tradingsymbol, scriprefkey, instrumentname, exchange, segment, strikeprice, expiry, lotsize) VALUES "
                sql_suffix = """
                ON CONFLICT (scripttoken) DO UPDATE SET
                    tradingsymbol = EXCLUDED.tradingsymbol,
                    scriprefkey = EXCLUDED.scriprefkey,
                    instrumentname = EXCLUDED.instrumentname,
                    exchange = EXCLUDED.exchange,
                    segment = EXCLUDED.segment,
                    strikeprice = EXCLUDED.strikeprice,
                    expiry = EXCLUDED.expiry,
                    lotsize = EXCLUDED.lotsize
                """
                for i in range(0, len(scrips_tuples), batch_size):
                    batch = scrips_tuples[i:i + batch_size]
                    placeholders = ",".join(["(%s, %s, %s, %s, %s, %s, %s, %s, %s)"] * len(batch))
                    flat_params = [item for row in batch for item in row]
                    cur.execute(sql_prefix + placeholders + sql_suffix, flat_params)
                    sys.stderr.write(f"[ScripLoader] Saved {min(i + batch_size, len(scrips_tuples))}/{len(scrips_tuples)} scrips...\n")
                    sys.stderr.flush()
                conn.commit()

            cur.execute("SELECT COUNT(*) FROM scrips")
            total = cur.fetchone()[0]
            sys.stderr.write(f"[ScripLoader] Complete. Total scrips in DB: {total}\n")
            sys.stderr.flush()
            return total

def load_category(cat_key: str, active_account: dict = None):
    candidate_urls = get_candidate_urls(cat_key, active_account)
    for target_url in candidate_urls:
        try:
            csv_bytes = download_bytes(target_url)
            scrips = parse_csv_scrips(csv_bytes)
            if scrips:
                exchange = scrips[0][4]
                total = save_scrips_to_postgres(scrips, exchange_to_clear=exchange)
                return {"success": True, "category": cat_key, "count": len(scrips), "totalCount": total}
        except Exception:
            continue
    return {"success": False, "error": f"Failed to download scrip master for {cat_key}"}

def load_daily_options(active_account: dict = None):
    # Load NIFTY and SENSEX options across NSE_FO and BSE_FO
    def option_filter(symbol, inst_name, segment, expiry_str):
        if segment not in ("CE", "PE"):
            return False
        sym_upper = symbol.upper()
        inst_upper = inst_name.upper()
        if "NIFTY" not in sym_upper and "NIFTY" not in inst_upper and "SENSEX" not in sym_upper and "SENSEX" not in inst_upper:
            return False
        if expiry_str:
            try:
                exp_d = datetime.fromisoformat(expiry_str).date()
                today = date.today()
                return today <= exp_d <= today + timedelta(days=35)
            except Exception:
                pass
        return True

    total_added = 0
    for cat_key in ["nse_fo", "bse_fo"]:
        for target_url in get_candidate_urls(cat_key, active_account):
            try:
                csv_bytes = download_bytes(target_url)
                scrips = parse_csv_scrips(csv_bytes, filter_fn=option_filter)
                if scrips:
                    total_added += len(scrips)
                    save_scrips_to_postgres(scrips, exchange_to_clear=None)
                    break
            except Exception:
                continue

    with psycopg.connect(DATABASE_URL, prepare_threshold=None) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM scrips")
            total = cur.fetchone()[0]

    return {"success": True, "loadedCount": total_added, "totalCount": total}

def clear_category(cat_key: str):
    cat_map = {"nse_fo": "NFO", "bse_fo": "BFO", "nse_cm": "NSE", "bse_cm": "BSE", "mcx_fo": "MCX", "cde_fo": "CDE"}
    ex = cat_map.get(cat_key.lower(), cat_key.upper())
    total = save_scrips_to_postgres([], exchange_to_clear=ex)
    return {"success": True, "category": cat_key, "totalCount": total}

def clear_all():
    with psycopg.connect(DATABASE_URL, prepare_threshold=None) as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM scrips")
            conn.commit()
    return {"success": True, "totalCount": 0}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"success": False, "error": "No command specified"}))
        sys.exit(1)

    cmd = sys.argv[1]
    active_account = None
    if len(sys.argv) >= 3 and sys.argv[-1].startswith("{"):
        try:
            active_account = json.loads(sys.argv[-1])
        except Exception:
            pass

    try:
        if cmd == "load_category":
            cat_key = sys.argv[2]
            res = load_category(cat_key, active_account)
        elif cmd == "load_daily_options":
            res = load_daily_options(active_account)
        elif cmd == "clear_category":
            cat_key = sys.argv[2]
            res = clear_category(cat_key)
        elif cmd == "clear_all":
            res = clear_all()
        else:
            res = {"success": False, "error": f"Unknown command {cmd}"}
        print(json.dumps(res))
    except Exception as e:
        print(json.dumps({"success": False, "error": str(e)}))
