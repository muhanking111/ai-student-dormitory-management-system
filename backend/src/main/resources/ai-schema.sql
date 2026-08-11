-- AI control-plane schema. This resource owns only ai_* tables.
-- It is loaded by AiSchemaMigrationInitializer so the legacy schema remains independent.

CREATE TABLE IF NOT EXISTS ai_model_deployment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  code VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  endpoint_alias VARCHAR(128) NOT NULL,
  capabilities_text LONGTEXT NOT NULL,
  data_region VARCHAR(64) NOT NULL,
  config_version INT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_model_deployment_public UNIQUE (public_id),
  CONSTRAINT uq_ai_model_deployment_code UNIQUE (code),
  INDEX idx_ai_model_deployment_enabled_provider (enabled, provider_code)
);

CREATE TABLE IF NOT EXISTS ai_model_alias (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  alias_code VARCHAR(64) NOT NULL,
  active_deployment_id BIGINT,
  version BIGINT NOT NULL DEFAULT 0,
  activated_by_user_id BIGINT,
  activated_at TIMESTAMP(6),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_model_alias_code UNIQUE (alias_code)
);

CREATE TABLE IF NOT EXISTS ai_prompt_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  prompt_key VARCHAR(128) NOT NULL,
  version VARCHAR(32) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  response_schema_version VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  active_slot_key VARCHAR(128),
  approved_by_user_id BIGINT,
  activated_at TIMESTAMP(6),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_prompt_key_version UNIQUE (prompt_key, version),
  CONSTRAINT uq_ai_prompt_active_slot UNIQUE (active_slot_key),
  INDEX idx_ai_prompt_key_status_active (prompt_key, status, activated_at)
);

CREATE TABLE IF NOT EXISTS ai_tool_catalog_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  version VARCHAR(32) NOT NULL,
  manifest_text LONGTEXT NOT NULL,
  manifest_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  active_slot_key VARCHAR(64),
  activated_at TIMESTAMP(6),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_tool_catalog_version UNIQUE (version),
  CONSTRAINT uq_ai_tool_catalog_manifest UNIQUE (manifest_hash),
  CONSTRAINT uq_ai_tool_catalog_active_slot UNIQUE (active_slot_key)
);

CREATE TABLE IF NOT EXISTS ai_pricing_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider_code VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  currency CHAR(3) NOT NULL,
  input_cost_per_million DECIMAL(19,6) NOT NULL,
  output_cost_per_million DECIMAL(19,6) NOT NULL,
  effective_from TIMESTAMP(6) NOT NULL,
  effective_to TIMESTAMP(6),
  source_reference VARCHAR(512) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_pricing_provider_model_from UNIQUE (provider_code, model_name, effective_from),
  INDEX idx_ai_pricing_effective_range (effective_from, effective_to)
);

CREATE TABLE IF NOT EXISTS ai_quota_policy (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type VARCHAR(32) NOT NULL,
  scope_key VARCHAR(128) NOT NULL,
  capability VARCHAR(64) NOT NULL,
  daily_token_limit BIGINT NOT NULL,
  monthly_cost_limit DECIMAL(19,6) NOT NULL,
  concurrent_run_limit INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  effective_from TIMESTAMP(6) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_ai_quota_limits_nonnegative CHECK (
    daily_token_limit >= 0 AND monthly_cost_limit >= 0 AND concurrent_run_limit >= 0
  ),
  CONSTRAINT uq_ai_quota_scope_effective UNIQUE (scope_type, scope_key, capability, effective_from),
  INDEX idx_ai_quota_status_effective (status, effective_from)
);

CREATE TABLE IF NOT EXISTS ai_step_up_grant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  grant_token_hmac CHAR(64) NOT NULL,
  grant_token_key_version INT NOT NULL,
  session_fingerprint_hash CHAR(64) NOT NULL,
  session_fingerprint_key_version INT NOT NULL,
  actor_user_id BIGINT NOT NULL,
  action_code VARCHAR(64) NOT NULL,
  resource_public_id CHAR(36),
  request_hash CHAR(64) NOT NULL,
  auth_method VARCHAR(32) NOT NULL,
  authenticated_at TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  state VARCHAR(32) NOT NULL,
  used_at TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_step_up_public UNIQUE (public_id),
  CONSTRAINT uq_ai_step_up_hmac UNIQUE (grant_token_hmac),
  INDEX idx_ai_step_up_actor_state_expires (actor_user_id, state, expires_at)
);

CREATE TABLE IF NOT EXISTS ai_step_up_failure_window (
  actor_user_id BIGINT PRIMARY KEY,
  window_started_at TIMESTAMP(6) NOT NULL,
  failure_count INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_ai_step_up_failure_count_nonnegative CHECK (failure_count >= 0)
);

CREATE TABLE IF NOT EXISTS ai_runtime_switch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type VARCHAR(32) NOT NULL,
  scope_key VARCHAR(128) NOT NULL,
  resource_public_id CHAR(36) NOT NULL,
  disabled BOOLEAN NOT NULL DEFAULT TRUE,
  reason_redacted VARCHAR(500) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_by_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_runtime_switch_scope UNIQUE (scope_type, scope_key),
  CONSTRAINT uq_ai_runtime_switch_public UNIQUE (resource_public_id),
  INDEX idx_ai_runtime_switch_disabled (disabled, scope_type)
);

CREATE TABLE IF NOT EXISTS ai_knowledge_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  name VARCHAR(200) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  owner_user_id BIGINT NOT NULL,
  classification VARCHAR(8) NOT NULL,
  permission_match_mode VARCHAR(8) NOT NULL,
  object_store_code VARCHAR(64) NOT NULL,
  acl_version BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_knowledge_source_public UNIQUE (public_id),
  INDEX idx_ai_knowledge_source_status_class (status, classification)
);

CREATE TABLE IF NOT EXISTS ai_knowledge_source_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_id BIGINT NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_source_permission UNIQUE (source_id, permission_code),
  INDEX idx_ai_source_permission_code_source (permission_code, source_id)
);

CREATE TABLE IF NOT EXISTS ai_knowledge_public_approval (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_id BIGINT NOT NULL,
  document_version_id BIGINT NOT NULL,
  decision VARCHAR(32) NOT NULL,
  approval_policy_version VARCHAR(64) NOT NULL,
  classification_snapshot VARCHAR(8) NOT NULL,
  acl_version BIGINT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  approval_snapshot_hash CHAR(64) NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  idempotency_record_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_public_approval_idempotency UNIQUE (idempotency_record_id),
  INDEX idx_ai_public_approval_source_version_created (source_id, document_version_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_upload_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  source_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  quarantine_object_key VARCHAR(512) NOT NULL,
  object_version_id VARCHAR(256),
  object_etag VARCHAR(256),
  expected_sha256 CHAR(64) NOT NULL,
  observed_sha256 CHAR(64),
  expected_size_bytes BIGINT NOT NULL,
  observed_size_bytes BIGINT,
  declared_mime_type VARCHAR(128) NOT NULL,
  detected_mime_type VARCHAR(128),
  scan_state VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  write_revoked_at TIMESTAMP(6),
  finalized_document_version_id BIGINT,
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_upload_public UNIQUE (public_id),
  CONSTRAINT uq_ai_upload_quarantine_key UNIQUE (quarantine_object_key),
  INDEX idx_ai_upload_owner_state_expires (owner_user_id, state, expires_at)
);

CREATE TABLE IF NOT EXISTS ai_document (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  source_id BIGINT NOT NULL,
  external_key_hmac CHAR(64) NOT NULL,
  external_key_key_version INT NOT NULL,
  title VARCHAR(500) NOT NULL,
  current_version_id BIGINT,
  status VARCHAR(32) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_document_public UNIQUE (public_id),
  CONSTRAINT uq_ai_document_source_external UNIQUE (source_id, external_key_hmac),
  INDEX idx_ai_document_source_status (source_id, status)
);

CREATE TABLE IF NOT EXISTS ai_document_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  document_id BIGINT NOT NULL,
  version VARCHAR(32) NOT NULL,
  content_hash CHAR(64) NOT NULL,
  visibility VARCHAR(32) NOT NULL,
  active_public_approval_id BIGINT,
  object_key VARCHAR(512) NOT NULL,
  object_version_id VARCHAR(256) NOT NULL,
  object_etag VARCHAR(256) NOT NULL,
  mime_type VARCHAR(128) NOT NULL,
  size_bytes BIGINT NOT NULL,
  parser_version VARCHAR(64) NOT NULL,
  chunk_policy_version VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  activated_at TIMESTAMP(6),
  retired_at TIMESTAMP(6),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_document_version_public UNIQUE (public_id),
  CONSTRAINT uq_ai_document_version_number UNIQUE (document_id, version),
  CONSTRAINT uq_ai_document_version_content UNIQUE (document_id, content_hash),
  INDEX idx_ai_document_version_status_visibility_active (status, visibility, activated_at)
);

CREATE TABLE IF NOT EXISTS ai_document_chunk (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  document_version_id BIGINT NOT NULL,
  chunk_no INT NOT NULL,
  content_redacted MEDIUMTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  locator_text LONGTEXT NOT NULL,
  metadata_text LONGTEXT NOT NULL,
  vector_ref VARCHAR(512),
  status VARCHAR(32) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_document_chunk_public UNIQUE (public_id),
  CONSTRAINT uq_ai_document_chunk_number UNIQUE (document_version_id, chunk_no),
  INDEX idx_ai_document_chunk_version_status (document_version_id, status),
  INDEX idx_ai_document_chunk_hash (content_hash)
);

CREATE TABLE IF NOT EXISTS ai_ingestion_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  document_version_id BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  attempt INT NOT NULL,
  worker_id VARCHAR(128),
  actor_kind VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
  service_principal_code VARCHAR(64) NOT NULL,
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  version BIGINT NOT NULL DEFAULT 0,
  available_at TIMESTAMP(6) NOT NULL,
  started_at TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  error_code VARCHAR(64),
  error_summary VARCHAR(500),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_ingestion_public UNIQUE (public_id),
  CONSTRAINT uq_ai_ingestion_version_attempt UNIQUE (document_version_id, attempt),
  INDEX idx_ai_ingestion_worker (state, available_at, id),
  INDEX idx_ai_ingestion_worker_state (worker_id, state)
);

CREATE TABLE IF NOT EXISTS ai_conversation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  owner_user_id BIGINT NOT NULL,
  surface VARCHAR(32) NOT NULL,
  context_type VARCHAR(32) NOT NULL,
  context_resource_id BIGINT,
  status VARCHAR(32) NOT NULL,
  title_redacted VARCHAR(500),
  last_message_at TIMESTAMP(6),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_conversation_public UNIQUE (public_id),
  INDEX idx_ai_conversation_owner_status_last (owner_user_id, status, last_message_at)
);

CREATE TABLE IF NOT EXISTS ai_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  conversation_id BIGINT NOT NULL,
  sequence_no BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  client_request_id VARCHAR(128),
  request_hash CHAR(64),
  content_redacted MEDIUMTEXT NOT NULL,
  raw_object_key VARCHAR(512),
  classification VARCHAR(8) NOT NULL,
  parent_message_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_message_public UNIQUE (public_id),
  CONSTRAINT uq_ai_message_sequence UNIQUE (conversation_id, sequence_no),
  CONSTRAINT uq_ai_message_client_request UNIQUE (conversation_id, client_request_id),
  INDEX idx_ai_message_conversation_created (conversation_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  parent_run_id BIGINT,
  conversation_id BIGINT NOT NULL,
  request_message_id BIGINT NOT NULL,
  capability VARCHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  actor_user_id BIGINT NOT NULL,
  session_fingerprint_hash CHAR(64) NOT NULL,
  session_fingerprint_key_version INT NOT NULL,
  permission_digest CHAR(64) NOT NULL,
  model_deployment_id BIGINT,
  prompt_version_id BIGINT NOT NULL,
  tool_catalog_version_id BIGINT NOT NULL,
  retrieval_policy_version VARCHAR(64) NOT NULL,
  redaction_policy_version VARCHAR(64) NOT NULL,
  quota_policy_id BIGINT,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  reserved_cost DECIMAL(19,6) NOT NULL DEFAULT 0,
  correlation_id CHAR(36) NOT NULL,
  started_at TIMESTAMP(6),
  first_token_at TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  estimated_cost DECIMAL(19,6) NOT NULL DEFAULT 0,
  cost_status VARCHAR(32) NOT NULL,
  failure_code VARCHAR(64),
  degrade_mode VARCHAR(64),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_run_public UNIQUE (public_id),
  CONSTRAINT uq_ai_run_correlation UNIQUE (correlation_id),
  CONSTRAINT uq_ai_run_request_message UNIQUE (request_message_id),
  INDEX idx_ai_run_parent (parent_run_id),
  INDEX idx_ai_run_conversation_state (conversation_id, state),
  INDEX idx_ai_run_actor_state_created (actor_user_id, state, created_at),
  INDEX idx_ai_run_capability_state_created (capability, state, created_at),
  INDEX idx_ai_run_deployment_created (model_deployment_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_run_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  sequence_no BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_redacted LONGTEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_run_event_sequence UNIQUE (run_id, sequence_no),
  INDEX idx_ai_run_event_run_created (run_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_tool_call (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  run_id BIGINT NOT NULL,
  sequence_no BIGINT NOT NULL,
  tool_name VARCHAR(128) NOT NULL,
  tool_version VARCHAR(32) NOT NULL,
  request_redacted LONGTEXT NOT NULL,
  response_redacted LONGTEXT,
  required_permissions_text LONGTEXT NOT NULL,
  authorization_decision VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  started_at TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  error_code VARCHAR(64),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_tool_call_public UNIQUE (public_id),
  CONSTRAINT uq_ai_tool_call_sequence UNIQUE (run_id, sequence_no),
  CONSTRAINT ck_ai_tool_call_authorization_decision CHECK (authorization_decision IN ('ALLOWED','DENIED')),
  INDEX idx_ai_tool_call_name_state_created (tool_name, state, created_at)
);

CREATE TABLE IF NOT EXISTS ai_retrieval_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  run_id BIGINT NOT NULL,
  query_hash CHAR(64) NOT NULL,
  retrieval_policy_version VARCHAR(64) NOT NULL,
  retrieval_mode VARCHAR(32) NOT NULL,
  index_code VARCHAR(64) NOT NULL,
  index_version VARCHAR(64) NOT NULL,
  embedding_model_version VARCHAR(64) NOT NULL,
  filter_redacted LONGTEXT NOT NULL,
  top_k INT NOT NULL,
  acl_pre_filter_count INT NOT NULL,
  acl_post_filter_count INT NOT NULL,
  returned_count INT NOT NULL,
  latency_ms BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_retrieval_trace_public UNIQUE (public_id),
  INDEX idx_ai_retrieval_trace_run (run_id),
  INDEX idx_ai_retrieval_trace_index_created (index_code, created_at)
);

CREATE TABLE IF NOT EXISTS ai_citation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  run_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  citation_type VARCHAR(32) NOT NULL,
  document_version_id BIGINT,
  chunk_id BIGINT,
  metric_id VARCHAR(128),
  rank_no INT NOT NULL,
  score DECIMAL(12,8),
  quote_redacted MEDIUMTEXT,
  locator_text LONGTEXT,
  content_hash CHAR(64),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_citation_run_public UNIQUE (run_id, public_id),
  INDEX idx_ai_citation_message_rank (message_id, rank_no)
);

CREATE TABLE IF NOT EXISTS ai_action_proposal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  run_id BIGINT NOT NULL,
  origin_tool_call_id BIGINT NOT NULL,
  action_type VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NOT NULL,
  target_resource_id BIGINT,
  payload_text LONGTEXT NOT NULL,
  preview_text LONGTEXT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  business_snapshot_hash CHAR(64) NOT NULL,
  required_business_permission VARCHAR(64) NOT NULL,
  approval_policy_version VARCHAR(64) NOT NULL,
  required_approval_count INT NOT NULL,
  approved_count INT NOT NULL DEFAULT 0,
  risk_level VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  proposer_user_id BIGINT NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_action_proposal_public UNIQUE (public_id),
  CONSTRAINT uq_ai_action_proposal_tool UNIQUE (origin_tool_call_id),
  INDEX idx_ai_action_proposal_state_expires (state, expires_at),
  INDEX idx_ai_action_proposal_type_created (action_type, created_at)
);

CREATE TABLE IF NOT EXISTS ai_action_approval (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  proposal_id BIGINT NOT NULL,
  proposal_version BIGINT NOT NULL,
  decision VARCHAR(32) NOT NULL,
  reviewer_user_id BIGINT NOT NULL,
  idempotency_record_id BIGINT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  business_snapshot_hash CHAR(64) NOT NULL,
  comment_redacted MEDIUMTEXT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_action_approval_reviewer UNIQUE (proposal_id, proposal_version, reviewer_user_id),
  CONSTRAINT uq_ai_action_approval_idempotency UNIQUE (idempotency_record_id),
  INDEX idx_ai_action_approval_reviewer_created (reviewer_user_id, created_at)
);

CREATE TABLE IF NOT EXISTS ai_idempotency_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_user_id BIGINT NOT NULL,
  route_code VARCHAR(64) NOT NULL,
  aggregate_public_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  response_status INT,
  response_resource_public_id CHAR(36),
  expires_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_idempotency_scope UNIQUE (actor_user_id, route_code, aggregate_public_id, idempotency_key),
  INDEX idx_ai_idempotency_state_expires (state, expires_at)
);

CREATE TABLE IF NOT EXISTS ai_action_execution (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  proposal_id BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  handler_name VARCHAR(128) NOT NULL,
  execution_key CHAR(64) NOT NULL,
  lease_token_hash CHAR(64) NOT NULL,
  executed_by_user_id BIGINT NOT NULL,
  reconfirmed_by_user_id BIGINT,
  result_resource_type VARCHAR(64),
  result_resource_id BIGINT,
  response_redacted LONGTEXT,
  started_at TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  error_code VARCHAR(64),
  error_summary VARCHAR(500),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_action_execution_public UNIQUE (public_id),
  CONSTRAINT uq_ai_action_execution_proposal UNIQUE (proposal_id),
  CONSTRAINT uq_ai_action_execution_key UNIQUE (execution_key),
  INDEX idx_ai_action_execution_state_started (state, started_at)
);

CREATE TABLE IF NOT EXISTS ai_risk_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  dedup_key CHAR(64) NOT NULL,
  active_dedup_key CHAR(64),
  risk_type VARCHAR(64) NOT NULL,
  subject_type VARCHAR(64) NOT NULL,
  subject_resource_id BIGINT,
  subject_token VARCHAR(128) NOT NULL,
  subject_token_key_version INT NOT NULL,
  severity VARCHAR(32) NOT NULL,
  state VARCHAR(32) NOT NULL,
  signal_policy_version VARCHAR(64) NOT NULL,
  signal_snapshot_redacted LONGTEXT NOT NULL,
  business_snapshot_redacted LONGTEXT NOT NULL,
  explanation_text_redacted LONGTEXT NOT NULL,
  explanation_basis VARCHAR(32) NOT NULL,
  explanation_policy_version VARCHAR(64) NOT NULL,
  explanation_run_id BIGINT,
  assignee_user_id BIGINT,
  opened_at TIMESTAMP(6) NOT NULL,
  due_at TIMESTAMP(6),
  resolved_at TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_risk_case_public UNIQUE (public_id),
  CONSTRAINT uq_ai_risk_case_active_dedup UNIQUE (active_dedup_key),
  INDEX idx_ai_risk_case_dedup_opened (dedup_key, opened_at),
  INDEX idx_ai_risk_case_state_severity_opened (state, severity, opened_at),
  INDEX idx_ai_risk_case_subject (subject_type, subject_resource_id)
);

CREATE TABLE IF NOT EXISTS ai_risk_case_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL,
  sequence_no BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  actor_kind VARCHAR(16) NOT NULL,
  actor_user_id BIGINT,
  service_principal_code VARCHAR(64),
  initiated_by_user_id BIGINT,
  idempotency_record_id BIGINT,
  case_version BIGINT NOT NULL,
  detail_redacted LONGTEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_risk_case_event_sequence UNIQUE (case_id, sequence_no),
  CONSTRAINT uq_ai_risk_case_event_idempotency UNIQUE (idempotency_record_id)
);

CREATE TABLE IF NOT EXISTS ai_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating INT NOT NULL,
  tags_text LONGTEXT,
  comment_redacted MEDIUMTEXT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_feedback_message_user UNIQUE (message_id, user_id),
  INDEX idx_ai_feedback_rating_created (rating, created_at)
);

CREATE TABLE IF NOT EXISTS ai_budget_bucket (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  quota_policy_id BIGINT NOT NULL,
  scope_type VARCHAR(32) NOT NULL,
  scope_key VARCHAR(128) NOT NULL,
  capability VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  period_type VARCHAR(32) NOT NULL,
  period_start TIMESTAMP(6) NOT NULL,
  period_end TIMESTAMP(6) NOT NULL,
  token_limit BIGINT NOT NULL,
  cost_limit DECIMAL(19,6) NOT NULL,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  committed_tokens BIGINT NOT NULL DEFAULT 0,
  reserved_cost DECIMAL(19,6) NOT NULL DEFAULT 0,
  committed_cost DECIMAL(19,6) NOT NULL DEFAULT 0,
  currency CHAR(3) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_ai_budget_bucket_nonnegative CHECK (
    token_limit >= 0 AND cost_limit >= 0 AND reserved_tokens >= 0 AND committed_tokens >= 0
    AND reserved_cost >= 0 AND committed_cost >= 0
  ),
  CONSTRAINT uq_ai_budget_bucket_scope_period UNIQUE (
    quota_policy_id, scope_type, scope_key, capability, provider_code, period_start
  ),
  INDEX idx_ai_budget_period_end (period_end)
);

CREATE TABLE IF NOT EXISTS ai_budget_reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  billing_subject_kind VARCHAR(16) NOT NULL,
  billing_subject_public_id CHAR(36) NOT NULL,
  budget_bucket_id BIGINT NOT NULL,
  reserved_tokens BIGINT NOT NULL,
  reserved_cost DECIMAL(19,6) NOT NULL,
  committed_tokens BIGINT NOT NULL DEFAULT 0,
  committed_cost DECIMAL(19,6) NOT NULL DEFAULT 0,
  state VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_ai_budget_reservation_nonnegative CHECK (
    reserved_tokens >= 0 AND reserved_cost >= 0 AND committed_tokens >= 0 AND committed_cost >= 0
    AND committed_tokens <= reserved_tokens AND committed_cost <= reserved_cost
  ),
  CONSTRAINT uq_ai_budget_reservation_public UNIQUE (public_id),
  CONSTRAINT uq_ai_budget_reservation_subject_bucket UNIQUE (
    billing_subject_kind, billing_subject_public_id, budget_bucket_id
  ),
  INDEX idx_ai_budget_reservation_state_expires (state, expires_at)
);

CREATE TABLE IF NOT EXISTS ai_provider_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  billing_subject_kind VARCHAR(16) NOT NULL,
  billing_subject_public_id CHAR(36) NOT NULL,
  request_sequence_no INT NOT NULL,
  attempt_no INT NOT NULL,
  request_kind VARCHAR(32) NOT NULL,
  actor_kind VARCHAR(16) NOT NULL,
  actor_user_id BIGINT,
  service_principal_code VARCHAR(64),
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  capability VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  pricing_version_id BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  input_cost_per_million DECIMAL(19,6) NOT NULL,
  output_cost_per_million DECIMAL(19,6) NOT NULL,
  estimation_policy_version VARCHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  owner_instance_id VARCHAR(64) NOT NULL,
  lease_expires_at TIMESTAMP(6) NOT NULL,
  input_tokens BIGINT,
  output_tokens BIGINT,
  cost_amount DECIMAL(19,6),
  usage_source VARCHAR(16),
  attempt_outcome VARCHAR(32),
  duration_ms BIGINT,
  failure_code VARCHAR(64),
  started_at TIMESTAMP(6) NOT NULL,
  finished_at TIMESTAMP(6),
  reconciliation_marked_at TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_ai_provider_attempt_nonnegative CHECK (
    input_cost_per_million >= 0 AND output_cost_per_million >= 0
    AND (input_tokens IS NULL OR input_tokens >= 0)
    AND (output_tokens IS NULL OR output_tokens >= 0)
    AND (cost_amount IS NULL OR cost_amount >= 0)
    AND (duration_ms IS NULL OR duration_ms >= 0)
  ),
  CONSTRAINT uq_ai_provider_attempt_public UNIQUE (public_id),
  CONSTRAINT uq_ai_provider_attempt_identity UNIQUE (
    billing_subject_kind, billing_subject_public_id, request_sequence_no, attempt_no
  ),
  INDEX idx_ai_provider_attempt_state_started (state, started_at, id),
  INDEX idx_ai_provider_attempt_state_lease (state, lease_expires_at, id),
  INDEX idx_ai_provider_attempt_subject_state (
    billing_subject_kind, billing_subject_public_id, state
  )
);

CREATE TABLE IF NOT EXISTS ai_usage_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  billing_subject_kind VARCHAR(16) NOT NULL,
  billing_subject_public_id CHAR(36) NOT NULL,
  request_sequence_no INT NOT NULL,
  attempt_no INT NOT NULL,
  request_kind VARCHAR(32) NOT NULL,
  actor_kind VARCHAR(16) NOT NULL,
  actor_user_id BIGINT,
  service_principal_code VARCHAR(64),
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  capability VARCHAR(64) NOT NULL,
  provider_code VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  provider_request_id_hash CHAR(64),
  pricing_version_id BIGINT,
  input_tokens BIGINT NOT NULL,
  output_tokens BIGINT NOT NULL,
  cost_amount DECIMAL(19,6) NOT NULL,
  currency CHAR(3) NOT NULL,
  usage_source VARCHAR(16) NOT NULL,
  estimation_policy_version VARCHAR(64),
  attempt_outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
  duration_ms BIGINT NOT NULL DEFAULT 0,
  failure_code VARCHAR(64),
  occurred_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_ai_usage_nonnegative CHECK (
    input_tokens >= 0 AND output_tokens >= 0 AND cost_amount >= 0
  ),
  CONSTRAINT uq_ai_usage_attempt UNIQUE (
    billing_subject_kind, billing_subject_public_id, request_sequence_no, attempt_no
  ),
  INDEX idx_ai_usage_actor_occurred (actor_user_id, occurred_at),
  INDEX idx_ai_usage_capability_occurred (capability, occurred_at)
);

CREATE TABLE IF NOT EXISTS ai_audit_chain_head (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  chain_scope VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_public_id CHAR(36) NOT NULL,
  last_sequence_no BIGINT NOT NULL DEFAULT 0,
  last_event_hash CHAR(64),
  integrity_key_version INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_audit_head_scope UNIQUE (chain_scope, aggregate_type, aggregate_public_id)
);

CREATE TABLE IF NOT EXISTS ai_audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  chain_scope VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_public_id CHAR(36) NOT NULL,
  sequence_no BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  actor_kind VARCHAR(16) NOT NULL,
  actor_user_id BIGINT,
  service_principal_code VARCHAR(64),
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  session_fingerprint_hash CHAR(64),
  session_fingerprint_key_version INT,
  permission_digest CHAR(64),
  payload_redacted_hash CHAR(64) NOT NULL,
  previous_event_hash CHAR(64),
  event_hash CHAR(64) NOT NULL,
  integrity_alg VARCHAR(32) NOT NULL,
  integrity_key_version INT NOT NULL,
  canonicalization_version VARCHAR(32) NOT NULL,
  correlation_id CHAR(36) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_audit_event_public UNIQUE (public_id),
  CONSTRAINT uq_ai_audit_event_sequence UNIQUE (
    chain_scope, aggregate_type, aggregate_public_id, sequence_no
  ),
  INDEX idx_ai_audit_event_actor_occurred (actor_user_id, occurred_at),
  INDEX idx_ai_audit_event_service_occurred (service_principal_code, occurred_at),
  INDEX idx_ai_audit_event_correlation (correlation_id)
);

CREATE TABLE IF NOT EXISTS ai_audit_anchor (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  anchor_date DATE NOT NULL,
  chain_scope VARCHAR(64) NOT NULL,
  root_hash CHAR(64) NOT NULL,
  event_count BIGINT NOT NULL,
  integrity_alg VARCHAR(32) NOT NULL,
  integrity_key_version INT NOT NULL,
  canonicalization_version VARCHAR(32) NOT NULL,
  external_sink_code VARCHAR(64) NOT NULL,
  external_receipt_hash CHAR(64),
  state VARCHAR(32) NOT NULL,
  anchored_at TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_audit_anchor_date_scope UNIQUE (anchor_date, chain_scope)
);

CREATE TABLE IF NOT EXISTS ai_eval_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  suite_name VARCHAR(128) NOT NULL,
  dataset_version VARCHAR(64) NOT NULL,
  prompt_version_id BIGINT NOT NULL,
  model_deployment_id BIGINT,
  code_revision VARCHAR(128) NOT NULL,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  actor_kind VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
  service_principal_code VARCHAR(64) NOT NULL,
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  started_at TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  summary_text LONGTEXT,
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_eval_run_public UNIQUE (public_id),
  INDEX idx_ai_eval_run_suite_started (suite_name, started_at)
);

CREATE TABLE IF NOT EXISTS ai_eval_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  eval_run_id BIGINT NOT NULL,
  case_key VARCHAR(128) NOT NULL,
  capability VARCHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  metrics_text LONGTEXT NOT NULL,
  failure_tags_text LONGTEXT,
  artifact_path VARCHAR(512),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_eval_result_case UNIQUE (eval_run_id, case_key),
  INDEX idx_ai_eval_result_capability_state (capability, state)
);

CREATE TABLE IF NOT EXISTS ai_outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_public_id CHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload_redacted LONGTEXT NOT NULL,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  available_at TIMESTAMP(6) NOT NULL,
  locked_by VARCHAR(128),
  actor_kind VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
  service_principal_code VARCHAR(64) NOT NULL,
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  locked_at TIMESTAMP(6),
  published_at TIMESTAMP(6),
  last_error_code VARCHAR(64),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_outbox_public_id UNIQUE (public_id),
  INDEX idx_ai_outbox_worker (state, available_at, id),
  INDEX idx_ai_outbox_aggregate (aggregate_type, aggregate_public_id)
);

CREATE TABLE IF NOT EXISTS ai_erasure_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  request_type VARCHAR(32) NOT NULL,
  scope_type VARCHAR(32) NOT NULL,
  scope_public_id CHAR(36) NOT NULL,
  requested_by_user_id BIGINT NOT NULL,
  legal_basis_code VARCHAR(64) NOT NULL,
  retention_hold BOOLEAN NOT NULL DEFAULT FALSE,
  state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  available_at TIMESTAMP(6) NOT NULL,
  finished_at TIMESTAMP(6),
  error_code VARCHAR(64),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_erasure_job_public UNIQUE (public_id),
  INDEX idx_ai_erasure_job_state_available (state, available_at)
);

CREATE TABLE IF NOT EXISTS ai_erasure_target (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  erasure_job_id BIGINT NOT NULL,
  target_kind VARCHAR(32) NOT NULL,
  target_ref_hash CHAR(64) NOT NULL,
  provider_code VARCHAR(64),
  state VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  proof_ref_hash CHAR(64),
  last_checked_at TIMESTAMP(6),
  last_error_code VARCHAR(64),
  created_operator_user_id BIGINT,
  updated_operator_user_id BIGINT,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_erasure_target_key UNIQUE (erasure_job_id, target_kind, target_ref_hash),
  INDEX idx_ai_erasure_target_state_checked (state, last_checked_at)
);

CREATE TABLE IF NOT EXISTS ai_risk_scan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  public_id CHAR(36) NOT NULL,
  requested_by_user_id BIGINT NOT NULL,
  actor_kind VARCHAR(16) NOT NULL DEFAULT 'SERVICE',
  service_principal_code VARCHAR(64) NOT NULL DEFAULT 'risk-scan',
  initiated_by_user_id BIGINT,
  effective_subject_user_id BIGINT,
  requested_role_codes_text LONGTEXT NOT NULL,
  requested_permission_codes_text LONGTEXT NOT NULL,
  requested_scope_text LONGTEXT NOT NULL,
  permission_digest CHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  provider_versions_text LONGTEXT NOT NULL,
  unavailable_providers_text LONGTEXT NOT NULL,
  signal_count INT NOT NULL DEFAULT 0,
  case_count INT NOT NULL DEFAULT 0,
  duplicate_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  version BIGINT NOT NULL DEFAULT 0,
  started_at TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_ai_risk_scan_public UNIQUE (public_id),
  INDEX idx_ai_risk_scan_actor_created (requested_by_user_id, created_at)
);
