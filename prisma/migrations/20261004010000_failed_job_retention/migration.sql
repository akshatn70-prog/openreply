create table if not exists public.openreply_failed_dm_job (
  job_id text primary key,
  queue_name text not null,
  job_name text not null,
  payload jsonb not null,
  attempts integer not null,
  error_message text not null,
  failed_at timestamptz not null default now()
);

create index if not exists openreply_failed_dm_job_failed_at_idx
  on public.openreply_failed_dm_job(failed_at);

create or replace function public.openreply_cleanup_queue_data()
returns void language plpgsql security definer set search_path=public,pgmq as $$
begin
  delete from public.openreply_queue_dedup where created_at < now()-interval '2 days';
  delete from public.openreply_worker_alert where created_at < now()-interval '2 days';
  delete from public.openreply_dm_rate_limit where window_started_at < now()-interval '2 days';
  delete from public.openreply_failed_dm_job where failed_at < now()-interval '24 hours';
end $$;

revoke all on function public.openreply_cleanup_queue_data() from public;
grant execute on function public.openreply_cleanup_queue_data() to postgres;
