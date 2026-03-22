alter table public.account_addons_public
  add column if not exists parser_preset text not null default 'GENERIC';

alter table public.account_addons_public
  drop constraint if exists account_addons_public_parser_preset_check;

alter table public.account_addons_public
  add constraint account_addons_public_parser_preset_check
  check (parser_preset in ('GENERIC', 'STREMTHRU', 'TORRENTIO', 'WEBSTREAMR'));

update public.account_addons_public
set parser_preset = 'GENERIC'
where parser_preset is null
   or trim(parser_preset) = '';
