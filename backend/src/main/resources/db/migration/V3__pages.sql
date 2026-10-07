-- Страницы становятся редактируемой сущностью: код служит первичным ключом,
-- заголовок расширяется до 200 символов, добавляются содержимое и время создания.
-- Назначения в user_pages переводятся со ссылки на page_id на ссылку pages(code).
-- V1 и V2 не изменяются, данные назначений переносятся, а не пересоздаются.

alter table pages add column content text;
alter table pages add column created_at timestamptz not null default now();
alter table pages alter column title type varchar(200);

-- Сначала добавляем и заполняем новую колонку назначений, потом меняем ключи:
-- иначе данные user_pages потеряются вместе с удаляемым page_id.
alter table user_pages add column page_code varchar(64);
update user_pages up set page_code = p.code from pages p where p.id = up.page_id;
alter table user_pages alter column page_code set not null;

alter table user_pages drop constraint user_pages_pkey;
alter table user_pages drop constraint user_pages_page_id_fkey;
alter table user_pages drop column page_id;
alter table user_pages add constraint user_pages_pkey primary key (user_id, page_code);

alter table pages drop constraint pages_code_key;
alter table pages drop constraint pages_pkey;
alter table pages drop column id;
alter table pages add constraint pages_pkey primary key (code);
drop sequence if exists pages_id_seq;

alter table user_pages
    add constraint user_pages_page_code_fkey
    foreign key (page_code) references pages (code) on delete cascade;

-- Содержимое трёх тестовых страниц переезжает из кода PageController в базу.
-- Заголовки не перезаписываются: их мог изменить администратор.
insert into pages (code, title, content) values
    ('TEST_PAGE_1', 'Тестовая страница 1',
     'Содержимое первой тестовой страницы. Доступ выдан администратором.'),
    ('TEST_PAGE_2', 'Тестовая страница 2',
     'Содержимое второй тестовой страницы. Доступ выдан администратором.'),
    ('TEST_PAGE_3', 'Тестовая страница 3',
     'Содержимое третьей тестовой страницы. Доступ выдан администратором.')
on conflict (code) do update
    set content = coalesce(pages.content, excluded.content);
