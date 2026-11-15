"""Exercise real Keycloak, Vault, bootstrap files, and auth-service in Docker.

All credentials here belong only to the isolated disposable dev stack.
Never print tokens, passwords, client secrets or raw response bodies.
"""

import json
import sys
import time
from http.cookies import SimpleCookie
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

VAULT = "http://vault:8200/v1/"
KEYCLOAK = "http://keycloak:8080/"
AUTH = "http://auth-service:8081/"
ROOT = {"X-Vault-Token": "docker-test-root"}


def request(url, data=None, headers=None, method=None, expected=200, form=False):
    headers = dict(headers or {})
    body = None
    if data is not None:
        body = (urlencode(data) if form else json.dumps(data)).encode()
        headers["Content-Type"] = (
            "application/x-www-form-urlencoded" if form else "application/json"
        )
    req = Request(url, data=body, headers=headers, method=method)
    try:
        response = urlopen(req, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == expected, (
            f"{req.get_method()} {url}: expected {expected}, got {response.status}"
        )
        content = response.read()
        return (json.loads(content) if content else None), response.headers


def wait_ready(url):
    for _ in range(240):
        try:
            request(url)
            return
        except (URLError, TimeoutError, AssertionError):
            time.sleep(0.5)
    raise AssertionError(f"Service did not become ready: {url}")


def vault(path, data=None, expected=200, headers=None, method=None):
    return request(VAULT + path, data, ROOT if headers is None else headers,
                   method=method, expected=expected)[0]


def prepare():
    wait_ready(VAULT + "sys/health")
    wait_ready(KEYCLOAK + "realms/sublio/.well-known/openid-configuration")
    admin, _ = request(
        KEYCLOAK + "realms/master/protocol/openid-connect/token",
        {"grant_type": "password", "client_id": "admin-cli",
         "username": "admin", "password": "docker-test-only"}, form=True,
    )
    headers = {"Authorization": "Bearer " + admin["access_token"]}
    clients, _ = request(
        KEYCLOAK + "admin/realms/sublio/clients?clientId=auth-service-bff",
        headers=headers,
    )
    assert len(clients) == 1, "Imported BFF client missing"
    secret, _ = request(
        KEYCLOAK + "admin/realms/sublio/clients/" + clients[0]["id"] + "/client-secret",
        headers=headers,
    )
    assert secret["value"], "Keycloak did not generate a BFF client secret"
    vault("secret/data/keycloak-client", {"data": {"client_secret": secret["value"]}})
    for name in ("auth-policy", "bootstrap-policy"):
        vault("sys/policies/acl/" + name,
              {"policy": Path("/policies/" + name + ".hcl").read_text()}, expected=204)
    vault("sys/auth/approle", {"type": "approle"}, expected=204)
    vault("auth/approle/role/auth-role", {
        "token_policies": ["auth-policy"], "token_ttl": "1h",
        "token_max_ttl": "4h", "secret_id_ttl": "10m", "secret_id_num_uses": 1,
    }, expected=204)
    vault("auth/token/create", {"id": "docker-test-bootstrap",
                               "policies": ["bootstrap-policy"], "ttl": "1h"})
    print("PASS: imported Keycloak BFF secret stored in Vault; AppRole and policies provisioned")


def cookie(headers):
    result = SimpleCookie()
    result.load(headers["Set-Cookie"])
    refresh = result["sublio_refresh"]
    assert refresh["httponly"], "Refresh cookie must be HttpOnly"
    return "sublio_refresh=" + refresh.value


def verify():
    # The actual auth container already consumed the bootstrap's single-use ID.
    role_id = Path("/vault/config/role-id").read_text().strip()
    used_id = Path("/vault/config/secret-id").read_text().strip()
    vault("auth/approle/login", {"role_id": role_id, "secret_id": used_id},
          headers={}, expected=400)
    print("PASS: auth-service authenticated using the bootstrap's single-use SecretID file")

    fresh = vault("auth/approle/role/auth-role/secret-id", {})["data"]["secret_id"]
    login = vault("auth/approle/login", {"role_id": role_id, "secret_id": fresh}, headers={})
    service = {"X-Vault-Token": login["auth"]["client_token"]}
    value = vault("secret/data/keycloak-client", headers=service)
    assert value["data"]["data"]["client_secret"]
    for path in ("secret/data/auth", "secret/data/keycloak",
                 "secret/metadata/keycloak-client", "database/creds/keycloak_role"):
        vault(path, headers=service, expected=403)
    vault("secret/data/keycloak-client", {"data": {"client_secret": "forbidden"}},
          headers=service, expected=403)
    print("PASS: auth-role can read only its client secret; other KV, database and write requests denied")

    credentials = {"email": "docker-test@example.com", "password": "Test-password-472!"}
    result, headers = request(AUTH + "register", credentials, expected=201)
    assert result["accessToken"], "Registration must auto-login"
    print("PASS: registration uses the BFF service account and auto-login succeeds")
    result, headers = request(AUTH + "login", credentials)
    assert result["accessToken"] and "refreshToken" not in result and "refresh_token" not in result
    browser = {"Cookie": cookie(headers), "Origin": "http://localhost:3000"}
    print("PASS: login works with the secret fetched from Vault")
    result, headers = request(AUTH + "refresh", headers=browser, method="POST")
    assert result["accessToken"], "Refresh must return an access token"
    browser["Cookie"] = cookie(headers)
    print("PASS: refresh succeeds through HttpOnly cookies")
    _, headers = request(AUTH + "logout", headers=browser, method="POST", expected=204)
    assert "Max-Age=0" in headers["Set-Cookie"], "Logout must clear the cookie"
    request(AUTH + "refresh", headers=browser, method="POST", expected=401)
    print("PASS: logout clears the cookie and revokes the Keycloak session")
    print("PASS: Docker end-to-end checks complete")


if __name__ == "__main__":
    {"prepare": prepare, "verify": verify}[sys.argv[1]]()
