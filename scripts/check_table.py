import psycopg
import os
from pathlib import Path

env_file = Path(__file__).parent.parent / ".env"
if env_file.exists():
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))

db_url = os.environ.get("DATABASE_URL")
with psycopg.connect(db_url) as conn:
    with conn.cursor() as cur:
        cur.execute("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'scrips'")
        print("COLUMNS:", cur.fetchall())
        cur.execute("SELECT constraint_name, constraint_type FROM information_schema.table_constraints WHERE table_name = 'scrips'")
        print("CONSTRAINTS:", cur.fetchall())
