-- VectorFlow Database Initialization Script
-- Runs once, the first time the Postgres volume is created.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users (JWT authentication + role-based access control)
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'VIEWER'
        CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN')),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Document metadata. The id is shared with the chunks stored in Qdrant.
CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    title VARCHAR(500) NOT NULL,
    chunk_count INT DEFAULT 0,
    status VARCHAR(50) DEFAULT 'processing',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_user_id ON documents(user_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_created_at ON documents(created_at DESC);

-- Development seed users (bcrypt hashes). CHANGE OR DELETE THESE IN PRODUCTION.
--   admin@vectorflow.dev  / admin123   (ADMIN)
--   editor@vectorflow.dev / editor123  (EDITOR)
INSERT INTO users (id, email, password_hash, role)
VALUES
    ('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', 'admin@vectorflow.dev',
     '$2b$10$qOMGHOlV1OolrpqxGSKHWeIKhZnuH20Cf7Zv.d2f7deWR1ah2.XRy', 'ADMIN'),
    ('b1ffcd88-8d1a-4ef8-bb6d-6bb9bd380a22', 'editor@vectorflow.dev',
     '$2b$10$WizhhwtZ5WAcgO2uZATF8eGMuP8KlvCwvtHKKywyzc3ttXwEfQzgO', 'EDITOR')
ON CONFLICT (email) DO NOTHING;
