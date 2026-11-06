import os

for k in [k for k in os.environ if k.startswith("GEMINI_API_KEY")]:
    del os.environ[k]
os.environ["ADMIN_TOKEN"] = "test-admin-token"
