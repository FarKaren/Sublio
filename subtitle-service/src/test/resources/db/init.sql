-- init.sql — тестовая инициализация схемы sublio
-- Соответствует Liquibase changesets 002, 003 и 004

CREATE SCHEMA IF NOT EXISTS sublio;

-- Расширение для gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 002-create-jobs-table
CREATE TABLE sublio.jobs (
                             id              UUID NOT NULL DEFAULT gen_random_uuid(),
                             user_id         UUID NOT NULL,
                             idempotency_key UUID NOT NULL,
                             status          VARCHAR(50) NOT NULL,
                             message         TEXT,
                             file_path       TEXT,
                             subtitle_id     UUID,
                             created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                             updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                             CONSTRAINT pk_jobs PRIMARY KEY (id),
                             CONSTRAINT uq_jobs_user_id_idempotency_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_jobs_user_id
    ON sublio.jobs (user_id);

-- 003-create-subtitles-table
CREATE TABLE sublio.subtitles (
                                  id         UUID NOT NULL DEFAULT gen_random_uuid(),
                                  job_id     UUID NOT NULL,
                                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  CONSTRAINT pk_subtitles PRIMARY KEY (id),
                                  CONSTRAINT fk_subtitles_job_id
                                      FOREIGN KEY (job_id)
                                          REFERENCES sublio.jobs (id)
);

-- 004-create-subtitle-entries-table
CREATE TABLE sublio.subtitle_entries (
                                         id            BIGINT GENERATED ALWAYS AS IDENTITY,
                                         subtitle_id   UUID NOT NULL,
                                         seq_num       INT NOT NULL,
                                         start_time    VARCHAR(20) NOT NULL,
                                         end_time      VARCHAR(20) NOT NULL,
                                         kanji_text    TEXT NOT NULL,
                                         hiragana_text TEXT NOT NULL,
                                         CONSTRAINT pk_subtitle_entries PRIMARY KEY (id),
                                         CONSTRAINT fk_subtitle_entries_subtitle_id
                                             FOREIGN KEY (subtitle_id)
                                                 REFERENCES sublio.subtitles (id)
);

CREATE INDEX idx_subtitle_entries_subtitle_id
    ON sublio.subtitle_entries (subtitle_id);