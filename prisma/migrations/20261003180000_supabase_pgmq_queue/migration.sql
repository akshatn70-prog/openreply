create extension if not exists pgmq;

do $$
begin
  if not exists (
    select 1 from pgmq.list_queues() where queue_name = 'dm_processing'
  ) then
    perform pgmq.create('dm_processing');
  end if;
end;
$$;

create table if not exists public.openreply_queue_dedup (
  dedup_key text primary key,
  queue_name text not null,
  created_at timestamptz not null default now()
);
create index if not exists openreply_queue_dedup_created_at_idx
  on public.openreply_queue_dedup(created_at);

create table if not exists public.openreply_worker_health (
  worker_id text primary key,
  status text not null default 'running',
  pid integer,
  hostname text,
  started_at timestamptz,
  checked_at timestamptz not null default now()
);

create table if not exists public.openreply_worker_alert (
  id bigint generated always as identity primary key,
  level text not null,
  message text not null,
  job_id text,
  instagram_account_id text,
  comment_id text,
  created_at timestamptz not null default now()
);
create index if not exists openreply_worker_alert_created_at_idx
  on public.openreply_worker_alert(created_at desc);

create table if not exists public.openreply_dm_rate_limit (
  instagram_account_id text primary key,
  window_started_at timestamptz not null default now(),
  reserved_count integer not null default 0
);

create or replace function public.openreply_reserve_dm_slot(
  p_account_id text, p_max integer, p_window_seconds integer
) returns table (allowed boolean, current_count integer, remaining integer)
language plpgsql security definer set search_path = public as $$
declare v_started timestamptz; v_count integer;
begin
  insert into public.openreply_dm_rate_limit(instagram_account_id)
  values (p_account_id) on conflict (instagram_account_id) do nothing;
  select window_started_at,reserved_count into v_started,v_count
  from public.openreply_dm_rate_limit
  where instagram_account_id=p_account_id for update;
  if extract(epoch from (clock_timestamp()-v_started)) >= p_window_seconds then
    v_started:=clock_timestamp(); v_count:=0;
    update public.openreply_dm_rate_limit
      set window_started_at=v_started,reserved_count=0
      where instagram_account_id=p_account_id;
  end if;
  if v_count >= p_max then
    return query select false,v_count,0; return;
  end if;
  v_count:=v_count+1;
  update public.openreply_dm_rate_limit set reserved_count=v_count
    where instagram_account_id=p_account_id;
  return query select true,v_count,greatest(p_max-v_count,0);
end $$;

create or replace function public.openreply_release_dm_slot(p_account_id text)
returns integer language plpgsql security definer set search_path=public as $$
declare v_count integer;
begin
  update public.openreply_dm_rate_limit
    set reserved_count=greatest(reserved_count-1,0)
    where instagram_account_id=p_account_id
    returning reserved_count into v_count;
  return coalesce(v_count,0);
end $$;

create or replace function public.openreply_record_worker_heartbeat(
  p_worker_id text,p_pid integer,p_hostname text,p_started_at timestamptz
) returns void language sql security definer set search_path=public as $$
  insert into public.openreply_worker_health(worker_id,pid,hostname,started_at,checked_at,status)
  values (p_worker_id,p_pid,p_hostname,p_started_at,now(),'running')
  on conflict(worker_id) do update set pid=excluded.pid,hostname=excluded.hostname,
    started_at=excluded.started_at,checked_at=excluded.checked_at,status='running';
$$;

create or replace function public.openreply_enqueue_dm_job(
  p_message jsonb,p_delay_seconds integer default 0,p_dedup_key text default null
) returns bigint language plpgsql security definer set search_path=public,pgmq as $$
declare v_id bigint;
begin
  if p_dedup_key is not null then
    insert into public.openreply_queue_dedup(dedup_key,queue_name)
    values(p_dedup_key,'dm_processing') on conflict(dedup_key) do nothing;
    if not found then return null; end if;
  end if;
  select pgmq.send('dm_processing',p_message,greatest(p_delay_seconds,0)) into v_id;
  return v_id;
end $$;

create or replace function public.openreply_cleanup_queue_data()
returns void language plpgsql security definer set search_path=public,pgmq as $$
begin
  delete from public.openreply_queue_dedup where created_at < now()-interval '2 days';
  delete from public.openreply_worker_alert where created_at < now()-interval '2 days';
  delete from public.openreply_dm_rate_limit where window_started_at < now()-interval '2 days';
  delete from pgmq.q_dm_processing where enqueued_at < now()-interval '1 day' and vt < now();
end $$;

revoke all on function public.openreply_reserve_dm_slot(text,integer,integer) from public;
revoke all on function public.openreply_release_dm_slot(text) from public;
revoke all on function public.openreply_record_worker_heartbeat(text,integer,text,timestamptz) from public;
revoke all on function public.openreply_enqueue_dm_job(jsonb,integer,text) from public;
revoke all on function public.openreply_cleanup_queue_data() from public;
grant execute on function public.openreply_reserve_dm_slot(text,integer,integer) to postgres;
grant execute on function public.openreply_release_dm_slot(text) to postgres;
grant execute on function public.openreply_record_worker_heartbeat(text,integer,text,timestamptz) to postgres;
grant execute on function public.openreply_enqueue_dm_job(jsonb,integer,text) to postgres;
grant execute on function public.openreply_cleanup_queue_data() to postgres;
