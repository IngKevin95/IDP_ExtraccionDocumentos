-- Origen del oficio sintetico: API (POST con marca sintetico=true) | IMPORT (archivo del generador).
alter table golden_set_document add column origen varchar(10) not null default 'IMPORT';
