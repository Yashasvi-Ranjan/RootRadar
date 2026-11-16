-- Used by docker-compose (mounted into /docker-entrypoint-initdb.d) and usable manually:
--   psql postgres -f scripts/db/create-databases.sql   (role must already exist; see README "Database setup")
-- The main database (apifd) is created by the Postgres image from POSTGRES_DB; this adds the integration-test database.
SELECT 'CREATE DATABASE apifd_test' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'apifd_test')\gexec
