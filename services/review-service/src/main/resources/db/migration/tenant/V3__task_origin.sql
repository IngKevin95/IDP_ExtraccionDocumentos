-- Metadatos de origen de la tarea para metricas de calidad (sin PII): tipologia y marca de muestreo ciego.
alter table review_task add column typology varchar(8);
alter table review_task add column blind_sample boolean not null default false;
