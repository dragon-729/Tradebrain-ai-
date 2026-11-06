from fastapi.testclient import TestClient

from app import ai
from app.keypool import KeyPool
from app.main import app

client = TestClient(app)  # no `with`: lifespan (DB) is not started


def test_admin_requires_token():
    assert client.get("/v1/admin/keys").status_code == 401
    assert client.get("/v1/admin/keys", headers={"X-Admin-Token": "wrong"}).status_code == 401


def test_admin_snapshot_masks_keys(monkeypatch):
    secret = "AIzaSyVERYSECRETKEYVALUE123456789"
    monkeypatch.setattr(ai, "pool", KeyPool([secret]))
    r = client.get("/v1/admin/keys", headers={"X-Admin-Token": "test-admin-token"})
    assert r.status_code == 200 and r.json()["total"] == 1
    assert secret not in r.text and r.json()["keys"][0]["key"] == "…6789"


def test_dashboard_served_with_csp():
    r = client.get("/admin/")
    assert r.status_code == 200 and "default-src 'self'" in r.headers["content-security-policy"]
