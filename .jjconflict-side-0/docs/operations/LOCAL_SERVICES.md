# Local Services and Persistence

This guide covers optional local infrastructure. Standard replay, scenario, and test workflows do not require a database service.

## XTDB

The local Compose file defines an XTDB PostgreSQL-wire service on port `5432`:

```bash
make xtdb        # start in the background
make db-setup    # run the repository's database setup task
make xtdb-logs   # inspect service logs
make xtdb-stop   # stop while retaining Compose resources
make xtdb-down   # stop and remove Compose resources
```

The development configuration in `config/docker-compose.yaml` uses the following local-only defaults:

| Setting | Value |
|---|---|
| Host port | `5432` |
| User | `xtdb` |
| Password | `xtdb` |
| Database | `xtdb` |

These credentials are for local development only. Do not use them for a shared or production deployment. Supply deployment-specific credentials and network policy outside this repository.

## Connectivity and lifecycle

1. Ensure Docker Compose is installed and the port is available.
2. Start the service with `make xtdb`.
3. Run the workflow that explicitly requires persistence, or `make db-setup` where applicable.
4. Inspect failures with `make xtdb-logs`.
5. Stop it with `make xtdb-stop`; use `make xtdb-down` when you want Compose resources removed.

The Compose file does not define a host volume. Treat the service as disposable development infrastructure unless you provide persistence explicitly.

## Forensic runner isolation

Forensic evidence execution is a separate operational surface. `docs/forensic/FORENSIC_HARDENING.md` describes implemented sealing, filesystem-isolation modes, and runtime checks. Its stronger isolation modes require Linux support for user and mount namespaces; failures fall back to shared-filesystem mode and are recorded in metadata.

Before relying on forensic output operationally, read:

- `docs/forensic/PRODUCTION_READINESS.md`
- `docs/forensic/PRODUCTION_GAPS.md`
- `docs/forensic/FORENSIC_HARDENING.md`

Those documents describe readiness limits; they are not replaced by this local-development guide.
