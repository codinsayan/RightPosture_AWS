-- RightPosture — Supabase schema and row-level security.
--
-- Paste this into the Supabase dashboard's SQL Editor and run it once, on a project created per
-- docs/iterations/v7.3-supabase-auth.md.
--
-- This is §13.2's Firestore mirror, redrawn relational. §13.1's Room schema is unchanged and
-- remains the source of truth: everything here is a mirror that the device pushes to, never the
-- other way round (§13.3).

-- ---------------------------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------------------------

create table if not exists public.users (
  id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('physio', 'patient')),
  name text,
  email text not null
);

-- §9.6: bundles are immutable, so an edit is a new row at version + 1, never an update. A session
-- recorded against version 1 can still name exactly what it was scored against afterwards.
create table if not exists public.exercises (
  id text not null,
  version int not null,
  physio_id uuid not null references public.users(id) on delete cascade,
  name text not null,
  rpose_path text not null,          -- Storage object path, not the bundle itself
  demo_path text,                    -- §9's optional demo.mp4
  -- §6.19: a hold is a parallel flow, not a flag, and the patient's screen has to say which of the
  -- two a row is before they tap it. Checked rather than free text: an unrecognised mode would
  -- reach the device and be rendered as neither.
  mode text not null default 'cyclic' check (mode in ('cyclic', 'hold')),
  hold_seconds int,                  -- §9.1's prescription.hold_s; null for a cyclic exercise
  created_at timestamptz not null default now(),
  primary key (id, version)
);

-- §0: the physio generates a six-character code, the patient enters it once.
create table if not exists public.invites (
  code text primary key,
  physio_id uuid not null references public.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  used_by uuid references public.users(id) on delete set null,
  used_at timestamptz
);

create table if not exists public.assignments (
  id uuid primary key default gen_random_uuid(),
  exercise_id text not null,
  exercise_version int not null,
  patient_id uuid not null references public.users(id) on delete cascade,
  reps int not null,
  sets int not null,
  rest_seconds int not null,
  -- What the physio prescribed, which need not be what they recorded: a thirty-second reference
  -- can be prescribed at fifteen for a patient starting out. Null falls back to the exercise's own.
  hold_seconds int,
  sequence_index int not null,
  active_from timestamptz not null default now(),
  active_to timestamptz,
  foreign key (exercise_id, exercise_version)
    references public.exercises(id, version) on delete cascade
);

create table if not exists public.sessions (
  id uuid primary key,
  assignment_id uuid references public.assignments(id) on delete set null,
  patient_id uuid references public.users(id) on delete cascade,
  exercise_id text,
  exercise_version int,
  started_at timestamptz not null,
  ended_at timestamptz,
  prescribed_reps int not null,
  set_score double precision,
  completion_rate double precision,
  degradation_onset_rep int,         -- §6.18.3's change point
  device_fps double precision,
  delegate text
);

-- Keyed on (session_id, index) rather than a surrogate id, which is what makes §7.5's retry
-- idempotent: replaying rep 4 hits the same row, so a duplicated sync is a no-op.
create table if not exists public.reps (
  session_id uuid not null references public.sessions(id) on delete cascade,
  rep_index int not null,
  quality double precision,          -- null means "not scored", never zero (§1.6)
  rom_degrees double precision,
  duration_ms int,
  valid boolean not null,
  rejection text,
  smoothness_sparc double precision, -- §6.18.5's SPARC. Named for what it holds, not §13.1's "jerk".
  primary key (session_id, rep_index)
);

create table if not exists public.feature_samples (
  session_id uuid not null,
  rep_index int not null,
  feature text not null,
  mean_z double precision,
  max_z double precision,
  mean_signed_z double precision,    -- §6.18.5 attributes on the signed mean
  frames int,
  primary key (session_id, rep_index, feature),
  foreign key (session_id, rep_index)
    references public.reps(session_id, rep_index) on delete cascade
);

create table if not exists public.fault_events (
  id uuid primary key,
  session_id uuid not null references public.sessions(id) on delete cascade,
  rep_index int not null,
  fault_id text not null,
  z_at_fire double precision not null,
  t_fire_ms int not null
);

create table if not exists public.cue_events (
  id uuid primary key,
  fault_event_id uuid not null references public.fault_events(id) on delete cascade,
  cue_variant text not null,
  z_at_fire double precision not null,
  z_at_2s double precision,
  z_next_rep double precision,
  resolved boolean not null default false
);

-- ---------------------------------------------------------------------------------------------
-- Ownership helpers
--
-- These exist because the obvious way to write the policies below does not work. A policy on
-- `exercises` that queries `assignments`, while the policy on `assignments` queries `exercises`,
-- is mutual recursion: Postgres refuses with 42P17 "infinite recursion detected in policy". That
-- is not a theoretical concern - the first version of this file did exactly that, and every table
-- but `invites` returned 500 against a real project.
--
-- SECURITY DEFINER runs the function as its owner and therefore skips RLS on the tables it reads,
-- which breaks the cycle. `search_path` is pinned because a SECURITY DEFINER function that resolves
-- names through the caller's path is a privilege-escalation hole.
-- ---------------------------------------------------------------------------------------------

create or replace function public.owns_exercise(ex_id text, ex_version int)
returns boolean language sql security definer set search_path = public stable as $fn$
  select exists (
    select 1 from public.exercises e
    where e.id = ex_id and e.version = ex_version and e.physio_id = auth.uid()
  );
$fn$;

create or replace function public.is_assigned_exercise(ex_id text, ex_version int)
returns boolean language sql security definer set search_path = public stable as $fn$
  select exists (
    select 1 from public.assignments a
    where a.exercise_id = ex_id and a.exercise_version = ex_version and a.patient_id = auth.uid()
  );
$fn$;

create or replace function public.owns_session(sess uuid)
returns boolean language sql security definer set search_path = public stable as $fn$
  select exists (select 1 from public.sessions s where s.id = sess and s.patient_id = auth.uid());
$fn$;

create or replace function public.physio_of_session(sess uuid)
returns boolean language sql security definer set search_path = public stable as $fn$
  select exists (
    select 1 from public.sessions s
    join public.exercises e on e.id = s.exercise_id and e.version = s.exercise_version
    where s.id = sess and e.physio_id = auth.uid()
  );
$fn$;

-- A patient must be able to read the name of the physio who prescribed to them.
--
-- 7.4 added the invite arm. A patient who has redeemed a code but has not been assigned anything
-- yet is still linked, and the screen that says "you are linked to <name>" has to work in exactly
-- that window - which is the whole gap between the two halves of 7.4's device gate.
create or replace function public.is_linked_physio(candidate uuid)
returns boolean language sql security definer set search_path = public stable as $fn$
  select exists (
    select 1 from public.assignments a
    join public.exercises e on e.id = a.exercise_id and e.version = a.exercise_version
    where a.patient_id = auth.uid() and e.physio_id = candidate
  ) or exists (
    select 1 from public.invites i
    where i.used_by = auth.uid() and i.physio_id = candidate
  );
$fn$;

-- 7.4. The mirror of the above, and the one that unblocks the iteration: a physio cannot assign an
-- exercise to a patient they cannot see. Without this every policy on `users` restricted a physio
-- to their own row, so the patient who had just redeemed their code was invisible and there was
-- nobody to assign to. The redeemed invite *is* the link - no separate join table is needed.
create or replace function public.is_linked_patient(candidate uuid)
returns boolean language sql security definer set search_path = public stable as $fn$
  select exists (
    select 1 from public.invites i
    where i.used_by = candidate and i.physio_id = auth.uid()
  );
$fn$;

-- ---------------------------------------------------------------------------------------------
-- Row-level security
--
-- Every table, without exception. PostgREST exposes these with the anon key, and the anon key
-- ships inside the APK, so a table with RLS disabled is readable by anyone who installs the app.
--
-- Policies are dropped before they are created so this file can be re-run after an edit.
-- ---------------------------------------------------------------------------------------------

alter table public.users            enable row level security;
alter table public.exercises        enable row level security;
alter table public.invites          enable row level security;
alter table public.assignments      enable row level security;
alter table public.sessions         enable row level security;
alter table public.reps             enable row level security;
alter table public.feature_samples  enable row level security;
alter table public.fault_events     enable row level security;
alter table public.cue_events       enable row level security;

drop policy if exists "read own user" on public.users;
drop policy if exists "insert own user" on public.users;
drop policy if exists "update own user" on public.users;
drop policy if exists "read linked physio" on public.users;
drop policy if exists "read linked patient" on public.users;

create policy "read own user" on public.users
  for select using (id = auth.uid());
create policy "insert own user" on public.users
  for insert with check (id = auth.uid());
create policy "update own user" on public.users
  for update using (id = auth.uid());
create policy "read linked physio" on public.users
  for select using (public.is_linked_physio(id));
create policy "read linked patient" on public.users
  for select using (public.is_linked_patient(id));

drop policy if exists "physio writes own exercises" on public.exercises;
drop policy if exists "assigned patient reads exercise" on public.exercises;

create policy "physio writes own exercises" on public.exercises
  for all using (physio_id = auth.uid()) with check (physio_id = auth.uid());
create policy "assigned patient reads exercise" on public.exercises
  for select using (public.is_assigned_exercise(id, version));

drop policy if exists "physio manages own invites" on public.invites;
drop policy if exists "signed-in user may redeem an open invite" on public.invites;
drop policy if exists "patient claims an open invite" on public.invites;

create policy "physio manages own invites" on public.invites
  for all using (physio_id = auth.uid()) with check (physio_id = auth.uid());

-- There is deliberately **no** patient-facing policy on `invites`. 7.4's first design gave patients
-- select and update on any open code, which had two faults. It let any signed-in user page through
-- every unredeemed code in the system, and redemption was a read followed by a write, so two
-- patients entering the same code could both read it as open and the second would overwrite the
-- first. `redeem_invite` below replaces both with one locked, atomic call.

drop policy if exists "patient reads own assignments" on public.assignments;
drop policy if exists "physio manages assignments on own exercises" on public.assignments;

create policy "patient reads own assignments" on public.assignments
  for select using (patient_id = auth.uid());
create policy "physio manages assignments on own exercises" on public.assignments
  for all using (public.owns_exercise(exercise_id, exercise_version))
  with check (public.owns_exercise(exercise_id, exercise_version));

drop policy if exists "patient writes own sessions" on public.sessions;
drop policy if exists "physio reads sessions on own exercises" on public.sessions;

create policy "patient writes own sessions" on public.sessions
  for all using (patient_id = auth.uid()) with check (patient_id = auth.uid());
-- This read is the entire point of section 14.1's report.
create policy "physio reads sessions on own exercises" on public.sessions
  for select using (public.owns_exercise(exercise_id, exercise_version));

drop policy if exists "session owner writes reps" on public.reps;
drop policy if exists "physio reads reps on own exercises" on public.reps;

create policy "session owner writes reps" on public.reps
  for all using (public.owns_session(session_id))
  with check (public.owns_session(session_id));
create policy "physio reads reps on own exercises" on public.reps
  for select using (public.physio_of_session(session_id));

drop policy if exists "session owner writes samples" on public.feature_samples;
drop policy if exists "physio reads samples on own exercises" on public.feature_samples;

create policy "session owner writes samples" on public.feature_samples
  for all using (public.owns_session(session_id))
  with check (public.owns_session(session_id));
create policy "physio reads samples on own exercises" on public.feature_samples
  for select using (public.physio_of_session(session_id));

drop policy if exists "session owner writes faults" on public.fault_events;
drop policy if exists "physio reads faults on own exercises" on public.fault_events;

create policy "session owner writes faults" on public.fault_events
  for all using (public.owns_session(session_id))
  with check (public.owns_session(session_id));
create policy "physio reads faults on own exercises" on public.fault_events
  for select using (public.physio_of_session(session_id));

drop policy if exists "session owner writes cues" on public.cue_events;

create policy "session owner writes cues" on public.cue_events
  for all using (
    exists (select 1 from public.fault_events f
            where f.id = fault_event_id and public.owns_session(f.session_id))
  ) with check (
    exists (select 1 from public.fault_events f
            where f.id = fault_event_id and public.owns_session(f.session_id))
  );

-- ---------------------------------------------------------------------------------------------
-- Redeeming an invite (§0's linking step)
--
-- One `SECURITY DEFINER` call rather than a policy, for three reasons.
--
-- Atomicity: `select ... for update` locks the row, so two patients racing on the same code
-- serialise and exactly one wins. A policy-guarded read-then-write cannot promise that.
--
-- Honest outcomes: 7.4 has to tell "expired" apart from "already used" apart from "no such code",
-- and a select policy tight enough to be safe returns an empty set for all three.
--
-- Least exposure: the patient never gains read access to `invites`, so there is no way to page
-- through live codes. The function reveals only the status of the one code that was typed in.
-- ---------------------------------------------------------------------------------------------

create or replace function public.redeem_invite(invite_code text)
returns table (status text, physio uuid)
language plpgsql security definer set search_path = public as $fn$
declare
  invite public.invites%rowtype;
begin
  if auth.uid() is null then
    return query select 'unauthenticated'::text, null::uuid;
    return;
  end if;

  select * into invite from public.invites where code = invite_code for update;

  if not found then
    return query select 'unknown'::text, null::uuid;
    return;
  end if;

  if invite.used_by is not null then
    -- Redeeming your own code twice is not an error. A patient who reinstalls, or who taps the
    -- button again because the first reply was lost, must end up linked to the same physio rather
    -- than being told their code is spent.
    if invite.used_by = auth.uid() then
      return query select 'ok'::text, invite.physio_id;
    else
      return query select 'used'::text, null::uuid;
    end if;
    return;
  end if;

  if invite.expires_at <= now() then
    return query select 'expired'::text, null::uuid;
    return;
  end if;

  update public.invites
     set used_by = auth.uid(), used_at = now()
   where code = invite_code;

  return query select 'ok'::text, invite.physio_id;
end;
$fn$;

-- The anon key ships inside the APK, so anything callable by `anon` is callable by anyone who
-- installs the app. Redeeming needs an identity to record, so it needs a signed-in caller.
--
-- `revoke ... from public` alone does **not** achieve that here, which was measured rather than
-- assumed: after the first version of this file was applied, an anon call still reached the
-- function and got a reply. Supabase grants EXECUTE on functions in `public` to `anon` and
-- `authenticated` explicitly through default privileges, and an explicit grant is not what
-- `from public` revokes. `anon` has to be named.
--
-- The `auth.uid() is null` guard at the top of the function stays regardless, and is the defence
-- that actually holds: a grant is configuration that a later migration or a dashboard action can
-- silently restore, whereas the guard travels with the function.
revoke all on function public.redeem_invite(text) from public;
revoke all on function public.redeem_invite(text) from anon;
grant execute on function public.redeem_invite(text) to authenticated;

-- ---------------------------------------------------------------------------------------------
-- Migrations for projects created before a column existed
-- ---------------------------------------------------------------------------------------------
--
-- `create table if not exists` above does nothing to a table that already exists, so a project
-- created before hold mode would keep the old shape and every push of a hold would fail on an
-- unknown column. These are idempotent and safe to re-run with the rest of the file.

alter table public.exercises   add column if not exists mode text not null default 'cyclic';
alter table public.exercises   add column if not exists hold_seconds int;
alter table public.assignments add column if not exists hold_seconds int;

do $$
begin
  -- Added separately from the column so re-running does not fail on a constraint already there.
  if not exists (
    select 1 from pg_constraint where conname = 'exercises_mode_check'
  ) then
    alter table public.exercises
      add constraint exercises_mode_check check (mode in ('cyclic', 'hold'));
  end if;
end $$;

-- ---------------------------------------------------------------------------------------------
-- Storage
-- ---------------------------------------------------------------------------------------------

-- One private bucket for .rpose bundles and demo.mp4. Private, not public: a demo video is a
-- recording of a real person, and the bundle describes a patient's programme.
insert into storage.buckets (id, name, public)
values ('exercises', 'exercises', false)
on conflict (id) do nothing;

drop policy if exists "owner uploads exercise objects" on storage.objects;
drop policy if exists "owner updates exercise objects" on storage.objects;
drop policy if exists "signed-in users read exercise objects" on storage.objects;

create policy "owner uploads exercise objects" on storage.objects
  for insert to authenticated with check (bucket_id = 'exercises' and owner = auth.uid());
create policy "owner updates exercise objects" on storage.objects
  for update to authenticated using (bucket_id = 'exercises' and owner = auth.uid());
create policy "signed-in users read exercise objects" on storage.objects
  for select to authenticated using (bucket_id = 'exercises');
