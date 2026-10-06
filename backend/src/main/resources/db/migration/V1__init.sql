create table users (
    id            uuid         primary key,
    email         varchar(320) not null unique,
    password_hash varchar(255) not null,
    role          varchar(32)  not null,
    enabled       boolean      not null default true,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null
);

create table pages (
    id    bigserial    primary key,
    code  varchar(64)  not null unique,
    title varchar(128) not null
);

create table user_pages (
    user_id uuid   not null references users (id) on delete cascade,
    page_id bigint not null references pages (id) on delete cascade,
    primary key (user_id, page_id)
);

create index idx_users_email on users (email);

insert into pages (code, title) values
    ('TEST_PAGE_1', 'Тестовая страница 1'),
    ('TEST_PAGE_2', 'Тестовая страница 2'),
    ('TEST_PAGE_3', 'Тестовая страница 3');