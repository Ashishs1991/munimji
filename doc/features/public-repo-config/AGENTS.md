# Public-repo config

Milestone 0. No domain behavior yet.

## What this covers

Secrets and local-only files stay out of git via `.gitignore`. Every application setting and API key is declared once in `src/main/resources/application.yml` as an environment placeholder. Local values belong in `.env`, copied from `.env.example`.

Spring loads `.env` with `spring.config.import: optional:file:.env[.properties]`. Real environment variables still override the file, which is how deployment should supply keys. The `.env` format is `KEY=value` without an `export` prefix.

## Spec

Repository safety in `doc/PRD.md` section 14 and `doc/RFC-001.md` section 15. No acceptance criteria for stock or money are implemented here.

## How to check

Confirm `.env` is ignored and that `application.yml` contains no literal keys:

```bash
git check-ignore -v .env
git grep -n -E 'sk-|service_role|BEGIN (RSA |OPENSSH )?PRIVATE' -- . ':(exclude).env.example' || true
```

## Still open

The Spring Boot application that consumes this file is milestone 1. Keys are named, not verified against a live Meta, Supabase, or WhatsApp account.
