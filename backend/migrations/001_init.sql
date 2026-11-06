-- TradeBrain AI schema (PostgreSQL + pgvector; designed for Supabase).
-- Authorization is enforced by the backend (every query filters by user_id). RLS below additionally
-- protects the tables if they are ever reached through Supabase PostgREST with a user token.
create extension if not exists vector;
create extension if not exists pgcrypto;

create table if not exists documents (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  title text not null,
  filename text,
  mime text,
  size_bytes bigint,
  sha256 text,
  category text,
  summary text,
  concepts jsonb not null default '[]',
  visibility text not null default 'private' check (visibility in ('private','public')),
  status text not null default 'processing' check (status in ('processing','ready','failed')),
  error text,
  page_count int,
  storage_path text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists documents_user_sha on documents(user_id, sha256);
create index if not exists documents_user_created on documents(user_id, created_at desc);
create index if not exists documents_public on documents(visibility) where visibility = 'public';

create table if not exists document_chunks (
  id bigserial primary key,
  document_id uuid not null references documents(id) on delete cascade,  -- delete doc => embeddings gone
  user_id uuid not null,
  page int not null,
  ord int not null,
  content text not null,
  embedding vector(768),  -- must equal EMBED_DIM
  tsv tsvector generated always as (to_tsvector('english', content)) stored
);
create index if not exists chunks_doc on document_chunks(document_id);
create index if not exists chunks_tsv on document_chunks using gin(tsv);
create index if not exists chunks_vec on document_chunks using hnsw (embedding vector_cosine_ops);

create table if not exists chart_analyses (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  title text,
  image bytea not null,
  mime text not null,
  result jsonb not null,
  note text,
  bookmarked boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists analyses_user on chart_analyses(user_id, created_at desc);

create table if not exists analysis_messages (
  id bigserial primary key,
  analysis_id uuid not null references chart_analyses(id) on delete cascade,
  role text not null check (role in ('user','assistant')),
  content text not null,
  created_at timestamptz not null default now()
);

create table if not exists ai_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null,
  title text,
  scope text not null default 'personal',
  created_at timestamptz not null default now()
);
create index if not exists conv_user on ai_conversations(user_id, created_at desc);

create table if not exists ai_messages (
  id bigserial primary key,
  conversation_id uuid not null references ai_conversations(id) on delete cascade,
  role text not null check (role in ('user','assistant')),
  content text not null,
  sources jsonb,
  created_at timestamptz not null default now()
);

-- Secure API key error/audit log: never stores key material, only id + last 4 chars.
create table if not exists api_key_events (
  id bigserial primary key,
  key_id text not null,
  fingerprint text not null,
  kind text not null,
  status int,
  message text,
  created_at timestamptz not null default now()
);
create index if not exists key_events_time on api_key_events(created_at desc);

-- Row Level Security
alter table documents enable row level security;
alter table document_chunks enable row level security;
alter table chart_analyses enable row level security;
alter table analysis_messages enable row level security;
alter table ai_conversations enable row level security;
alter table ai_messages enable row level security;
alter table api_key_events enable row level security;  -- no policies: backend/service role only

create policy documents_owner on documents for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy documents_public_read on documents for select using (visibility = 'public' and status = 'ready');
create policy chunks_owner on document_chunks for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy chunks_public_read on document_chunks for select using (
  exists (select 1 from documents d where d.id = document_id and d.visibility = 'public' and d.status = 'ready'));
create policy analyses_owner on chart_analyses for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy analysis_msgs_owner on analysis_messages for all using (
  exists (select 1 from chart_analyses a where a.id = analysis_id and a.user_id = auth.uid()));
create policy conv_owner on ai_conversations for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy ai_msgs_owner on ai_messages for all using (
  exists (select 1 from ai_conversations c where c.id = conversation_id and c.user_id = auth.uid()));
