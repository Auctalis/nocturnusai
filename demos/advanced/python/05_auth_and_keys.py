"""
05_auth_and_keys.py — Authentication and API key management

Demonstrates:
  - auth_status() — check if auth is enabled
  - bootstrap() — create the first admin key (only works once)
  - create_key() — create scoped keys with roles
  - list_keys() — inspect all active keys
  - whoami() — identify the current key
  - revoke_key() — invalidate a key

NOTE: Auth must be enabled on the server for most of these to take effect.
      Set AUTH_ENABLED=true in your environment before starting the server.
      If AUTH_ENABLED=false, auth endpoints still work but keys are not enforced.
"""

from nocturnusai import SyncNocturnusAIClient
from nocturnusai.exceptions import NocturnusAIAPIError

SERVER = "http://localhost:9300"


def main():
    # Start with no key (unauthenticated or pre-bootstrap)
    with SyncNocturnusAIClient(SERVER) as anon:
        print("=== 1. Auth status ===")
        status = anon.auth_status()
        print(f"  authEnabled: {status.get('authEnabled')}")
        print(f"  mode:        {status.get('mode')}")
        print(f"  hasKeys:     {status.get('hasKeys')}")

        if not status.get("hasKeys"):
            print("\n=== 2. Bootstrap (first admin key) ===")
            try:
                result = anon.bootstrap(name="admin-demo", description="Demo admin key")
                admin_key = result.get("key")
                admin_id = result.get("id")
                print(f"  Admin key created: id={admin_id}")
                print(f"  Raw key (save this — shown only once): {admin_key[:12]}...")
            except NocturnusAIAPIError as e:
                print(f"  Bootstrap unavailable: {e}")
                admin_key = None
                admin_id = None
        else:
            print("\n=== 2. Already bootstrapped — skipping ===")
            admin_key = None
            admin_id = None

    if not admin_key:
        print("\nNo admin key available. Set AUTH_ENABLED=true and re-run on a fresh server.")
        return

    # Use the admin key for subsequent operations
    with SyncNocturnusAIClient(SERVER, api_key=admin_key) as admin:

        print("\n=== 3. Whoami ===")
        me = admin.whoami()
        print(f"  keyId: {me.get('keyId')}")
        print(f"  name:  {me.get('name')}")
        print(f"  role:  {me.get('role')}")

        print("\n=== 4. Create a scoped writer key ===")
        writer = admin.create_key(
            name="agent-writer",
            role="writer",
            databases=["production"],
            tenants=["tenant-a"],
            expires_in_days=30,
            description="Writer key for agent-writer demo",
        )
        writer_key = writer.get("key")
        writer_id = writer.get("id")
        print(f"  Writer key created: id={writer_id}, prefix={writer.get('prefix')}")

        print("\n=== 5. Create a read-only key ===")
        reader = admin.create_key(
            name="dashboard-reader",
            role="reader",
            description="Read-only dashboard key",
        )
        reader_id = reader.get("id")
        print(f"  Reader key created: id={reader_id}")

        print("\n=== 6. List all keys ===")
        keys = admin.list_keys()
        print(f"  {len(keys)} active key(s):")
        for k in keys:
            print(f"    [{k.get('role'):6}] {k.get('name')} (id={k.get('id')[:8]}...)")

        print("\n=== 7. Revoke the reader key ===")
        admin.revoke_key(reader_id)
        print(f"  Revoked: {reader_id}")

        keys_after = admin.list_keys()
        print(f"  Keys remaining: {len(keys_after)}")

        print("\n=== 8. Use writer key to assert a fact ===")
        with SyncNocturnusAIClient(SERVER, api_key=writer_key, database="production") as writer_client:
            writer_client.ensure_database()
            writer_client.assert_fact("demo", ["auth-works"])
            facts = writer_client.query("demo", ["?x"])
            print(f"  Writer successfully asserted and queried: {len(facts)} fact(s)")

    print("\nDone.")


if __name__ == "__main__":
    main()
