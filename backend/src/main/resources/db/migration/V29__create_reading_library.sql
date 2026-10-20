CREATE TABLE reading_book (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    title VARCHAR(120) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 120),
    source_description VARCHAR(1000) NOT NULL DEFAULT '',
    latest_version_id UUID,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(id,owner_id)
);
CREATE TABLE book_import (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(255) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    file_bytes BIGINT NOT NULL CHECK (file_bytes BETWEEN 1 AND 20971520),
    owns_file BOOLEAN NOT NULL DEFAULT TRUE,
    title VARCHAR(120) NOT NULL,
    source_description VARCHAR(1000) NOT NULL DEFAULT '',
    parser_id VARCHAR(40) NOT NULL,
    heading_level INTEGER,
    parser_version INTEGER NOT NULL DEFAULT 1 CHECK (parser_version=1),
    preview_hash CHAR(64),
    preview_json JSONB,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PARSING','PREVIEW_READY','CONFIRMED','FAILED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    target_book_id UUID,
    result_version_id UUID,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id,client_request_id),
    FOREIGN KEY(target_book_id,owner_id) REFERENCES reading_book(id,owner_id)
);
CREATE TABLE book_version (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    book_id UUID NOT NULL,
    source_import_id UUID NOT NULL UNIQUE REFERENCES book_import(id),
    file_hash CHAR(64) NOT NULL,
    text_hash CHAR(64) NOT NULL,
    parser_id VARCHAR(40) NOT NULL,
    parser_version INTEGER NOT NULL DEFAULT 1,
    options_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PREPARING','READY','FAILED')),
    chapter_count INTEGER NOT NULL CHECK (chapter_count BETWEEN 1 AND 5000),
    paragraph_count INTEGER NOT NULL CHECK (paragraph_count BETWEEN 1 AND 200000),
    character_count INTEGER NOT NULL CHECK (character_count BETWEEN 1 AND 5000000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(book_id,owner_id) REFERENCES reading_book(id,owner_id),
    UNIQUE(owner_id,file_hash,parser_id,parser_version,options_hash),
    UNIQUE(id,book_id,owner_id),
    UNIQUE(id,owner_id),
    UNIQUE(id,book_id)
);
ALTER TABLE reading_book ADD CONSTRAINT reading_book_latest_fk
    FOREIGN KEY(latest_version_id,id) REFERENCES book_version(id,book_id);
ALTER TABLE book_import ADD CONSTRAINT book_import_result_fk
    FOREIGN KEY(result_version_id) REFERENCES book_version(id);
CREATE TABLE book_chapter (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES book_version(id),
    chapter_ordinal INTEGER NOT NULL CHECK (chapter_ordinal > 0),
    title TEXT NOT NULL,
    first_paragraph_ordinal INTEGER NOT NULL CHECK (first_paragraph_ordinal > 0),
    last_paragraph_ordinal INTEGER NOT NULL CHECK (last_paragraph_ordinal >= first_paragraph_ordinal),
    text_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(version_id,chapter_ordinal),
    UNIQUE(id,version_id)
);
CREATE TABLE book_paragraph (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    paragraph_ordinal INTEGER NOT NULL CHECK (paragraph_ordinal > 0),
    chapter_paragraph_ordinal INTEGER NOT NULL CHECK (chapter_paragraph_ordinal > 0),
    text TEXT NOT NULL CHECK (length(text) BETWEEN 1 AND 4000),
    code_point_count INTEGER NOT NULL CHECK (code_point_count=length(text)),
    text_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(chapter_id,version_id) REFERENCES book_chapter(id,version_id),
    UNIQUE(version_id,paragraph_ordinal),
    UNIQUE(chapter_id,chapter_paragraph_ordinal),
    UNIQUE(id,version_id)
);
CREATE TABLE reading_state (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    book_id UUID NOT NULL,
    version_id UUID NOT NULL,
    resume_paragraph_id UUID,
    resume_offset INTEGER NOT NULL DEFAULT 0 CHECK (resume_offset BETWEEN 0 AND 4000),
    resume_revision BIGINT NOT NULL DEFAULT 0 CHECK (resume_revision>=0),
    last_read_at TIMESTAMPTZ,
    boundary_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (boundary_ordinal>=0),
    scope_revision BIGINT NOT NULL DEFAULT 0 CHECK (scope_revision>=0),
    visibility_epoch BIGINT NOT NULL DEFAULT 0 CHECK (visibility_epoch>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(version_id,book_id,owner_id) REFERENCES book_version(id,book_id,owner_id),
    FOREIGN KEY(resume_paragraph_id,version_id) REFERENCES book_paragraph(id,version_id),
    UNIQUE(owner_id,version_id)
);
CREATE TABLE reading_state_command (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    version_id UUID NOT NULL REFERENCES book_version(id),
    client_request_id UUID NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(owner_id,client_request_id)
);
CREATE TABLE reading_bookmark (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    book_id UUID NOT NULL,
    version_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    paragraph_id UUID NOT NULL,
    position_offset INTEGER NOT NULL CHECK (position_offset BETWEEN 0 AND 4000),
    label VARCHAR(120) NOT NULL DEFAULT '',
    required_boundary INTEGER NOT NULL CHECK (required_boundary>=0),
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(version_id,book_id,owner_id) REFERENCES book_version(id,book_id,owner_id),
    FOREIGN KEY(paragraph_id,version_id) REFERENCES book_paragraph(id,version_id),
    UNIQUE(owner_id,client_request_id)
);
CREATE TABLE reading_note (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    book_id UUID NOT NULL,
    version_id UUID NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('MANUAL','EXCERPT')),
    title VARCHAR(120) NOT NULL DEFAULT '',
    body TEXT NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 5000),
    anchor_paragraph_id UUID,
    anchor_start INTEGER,
    anchor_end INTEGER,
    required_boundary INTEGER NOT NULL CHECK (required_boundary>=0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted SMALLINT NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    FOREIGN KEY(version_id,book_id,owner_id) REFERENCES book_version(id,book_id,owner_id),
    FOREIGN KEY(anchor_paragraph_id,version_id) REFERENCES book_paragraph(id,version_id),
    CHECK ((anchor_paragraph_id IS NULL AND anchor_start IS NULL AND anchor_end IS NULL)
        OR (anchor_paragraph_id IS NOT NULL AND anchor_start>=0 AND anchor_end>=anchor_start AND anchor_end<=4000)),
    CHECK (kind<>'EXCERPT' OR anchor_paragraph_id IS NOT NULL),
    UNIQUE(owner_id,client_request_id)
);
CREATE INDEX reading_book_owner_idx ON reading_book(owner_id,created_at DESC,id) WHERE deleted=0;
CREATE INDEX book_import_owner_idx ON book_import(owner_id,status,expires_at) WHERE deleted=0;
CREATE INDEX reading_note_version_idx ON reading_note(owner_id,version_id,created_at DESC,id) WHERE deleted=0;
CREATE INDEX reading_bookmark_version_idx ON reading_bookmark(owner_id,version_id,created_at DESC,id) WHERE deleted=0;
CREATE UNIQUE INDEX reading_bookmark_active_position_uq ON reading_bookmark(owner_id,version_id,paragraph_id,position_offset) WHERE deleted=0;
