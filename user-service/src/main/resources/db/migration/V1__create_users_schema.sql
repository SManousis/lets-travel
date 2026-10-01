CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE travelers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    passport_number VARCHAR(128) NOT NULL,
    nationality VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX travelers_user_idx ON travelers (user_id);
