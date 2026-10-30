#!/usr/bin/env bash
# End-to-end demo of the SDLC orchestrator through its REST API.
#
# Prerequisites: the orchestrator is running (./mvnw -pl orchestrator-app spring-boot:run),
# curl, and python3 (used only to parse JSON; jq is not required).
#
# Usage:
#   scripts/demo.sh                      # greenfield scenario, deterministic agents
#   scripts/demo.sh brownfield           # any scenario key from GET /api/v1/scenarios
#   PROVIDER=anthropic scripts/demo.sh   # run with an LLM provider (needs ANTHROPIC_API_KEY on the server)
#
# Environment:
#   ORCHESTRATOR_URL  default http://localhost:8090
#   OPERATOR          operator identity sent as X-Operator-Id (default demo-operator)
#   PROVIDER          deterministic | anthropic | ollama (default: server default)
#   TIMEOUT_SECONDS   how long to drive the run before giving up (default 300)
#   SDLC_API_KEY      X-Api-Key of a control plane in api-key mode (default: SDLC_SECURITY_API_KEY, the variable
#                     docker-compose.yml passes to the control plane, so one exported key serves both)

set -euo pipefail

BASE="${ORCHESTRATOR_URL:-http://localhost:8090}"
OPERATOR="${OPERATOR:-demo-operator}"
SCENARIO="${1:-greenfield}"
PROVIDER="${PROVIDER:-}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

# Pick a working Python (on Windows, "python3" may be a Microsoft Store stub that only prints a hint).
PY=""
for candidate in python3 python; do
  if "$candidate" -c "import sys; sys.exit(0)" >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done
if [[ -z "$PY" ]]; then
  echo "python3 (or python) is required to parse JSON responses" >&2
  exit 1
fi

# tr strips the carriage returns a Windows Python writes to stdout, which would otherwise break URLs built from ids.
json() { "$PY" -c "import sys, json; d = json.load(sys.stdin); $1" | tr -d '\r'; }

# When the control plane runs in api-key mode, export SDLC_API_KEY (or SDLC_SECURITY_API_KEY) to authenticate.
API_KEY="${SDLC_API_KEY:-${SDLC_SECURITY_API_KEY:-}}"

api() {
  local method="$1" path="$2" body="${3:-}"
  local -a headers=(-H "X-Operator-Id: $OPERATOR")
  [[ -n "$API_KEY" ]] && headers+=(-H "X-Api-Key: $API_KEY")
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$BASE$path" -H "Content-Type: application/json" "${headers[@]}" -d "$body"
  else
    curl -sS -X "$method" "$BASE$path" "${headers[@]}"
  fi
}

echo "== SDLC Orchestrator demo =="
echo "server:   $BASE"
echo "scenario: $SCENARIO"
echo "operator: $OPERATOR"
[[ -n "$PROVIDER" ]] && echo "provider: $PROVIDER"
echo

echo "-- health"
curl -sS "$BASE/actuator/health" | json 'print("  status:", d.get("status"))'

echo "-- scenario"
api GET "/api/v1/scenarios" | json "
for s in d:
    if s['key'] == '$SCENARIO':
        print('  ' + s['title']); print('  ' + s['requirement'])
"

echo "-- starting run"
QUERY=""
[[ -n "$PROVIDER" ]] && QUERY="?provider=$PROVIDER"
RUN=$(api POST "/api/v1/scenarios/$SCENARIO/run$QUERY" "{}")
RUN_ID=$(printf '%s' "$RUN" | json 'print(d["id"])')
echo "  run id: $RUN_ID"
echo

answer_for() {
  # Canned clarification answers for the ambiguous scenario; adjust freely.
  case "$1" in
    *faster*|*latency*|*operations*) echo "Redirect endpoint: p95 latency under 30 ms at 500 requests per second; create endpoint under 100 ms." ;;
    *reliab*|*availab*) echo "Availability 99.9 percent monthly, error rate below 0.1 percent, no data loss on restart." ;;
    *) echo "Scope is the redirect and create endpoints of the existing URL shortener; no infrastructure changes beyond caching." ;;
  esac
}

start=$(date +%s)
last_status=""
while true; do
  VIEW=$(api GET "/api/v1/runs/$RUN_ID")
  STATUS=$(printf '%s' "$VIEW" | json 'print(d["status"])')
  SUMMARY=$(printf '%s' "$VIEW" | json 'print(" ".join(s["id"]+"="+s["status"] for s in d["stages"]))')
  if [[ "$STATUS $SUMMARY" != "$last_status" ]]; then
    echo "[$STATUS] $SUMMARY"
    last_status="$STATUS $SUMMARY"
  fi

  case "$STATUS" in
    COMPLETED|ABORTED|SAFE_STOPPED) break ;;
  esac

  if [[ "$STATUS" == "WAITING_HUMAN" ]]; then
    # Approvals
    printf '%s' "$VIEW" | json 'print("\n".join(a["stageId"] for a in d.get("pendingActions", []) if a["type"] == "APPROVAL"))' | while read -r stage; do
      [[ -z "$stage" ]] && continue
      echo "  -> approving gate at stage '$stage' as $OPERATOR (demo auto-approval; a real operator reviews the artifact first)"
      api POST "/api/v1/runs/$RUN_ID/stages/$stage/approve" '{"comment":"Auto-approved by scripts/demo.sh for demonstration purposes; not a human review"}' >/dev/null
    done
    # Clarifications
    QUESTIONS=$(printf '%s' "$VIEW" | json 'qs=[q for a in d.get("pendingActions", []) if a["type"]=="CLARIFICATION" for q in a["questions"]]; print("\n".join(q["id"]+"|"+q["question"] for q in qs))')
    if [[ -n "$QUESTIONS" ]]; then
      ANSWERS="{"
      first=1
      while IFS='|' read -r qid qtext; do
        [[ -z "$qid" ]] && continue
        ans=$(answer_for "$(printf '%s' "$qtext" | tr '[:upper:]' '[:lower:]')")
        echo "  -> clarifying $qid: $ans"
        [[ $first -eq 0 ]] && ANSWERS+=","
        ANSWERS+="\"$qid\":\"$ans\""
        first=0
      done <<< "$QUESTIONS"
      ANSWERS+="}"
      api POST "/api/v1/runs/$RUN_ID/clarify" "{\"answers\":$ANSWERS}" >/dev/null
    fi
  fi

  now=$(date +%s)
  if (( now - start > TIMEOUT_SECONDS )); then
    echo "timed out after ${TIMEOUT_SECONDS}s"; break
  fi
  sleep 1
done

echo
echo "-- final state: $STATUS"
printf '%s' "$VIEW" | json '
print("  halt reason:", d.get("haltReason")); print("  plan version:", d.get("planVersion"))
c = d.get("counters", {}); print("  counters:", ", ".join(f"{k}={v}" for k, v in c.items()))
t = d.get("timing", {}); print("  timing:", ", ".join(f"{k}={v}" for k, v in t.items() if v is not None))
'

echo
echo "-- audit trail (last 15 events)"
api GET "/api/v1/runs/$RUN_ID/audit" | json '
for e in d[-15:]:
    stage = e.get("stageId") or "-"
    actor = e["actor"]["type"] + ":" + e["actor"]["id"]
    print("  %4d  %s  %-18s %-24s %s  %s" % (e["seq"], e["timestamp"], stage, e["type"], actor, e["message"]))
'

echo
echo "-- artifacts"
api GET "/api/v1/runs/$RUN_ID/artifacts" | json '
for a in d:
    print("  %-18s v%-3d %-22s %s  accepted=%s" % (a["stageId"], a["version"], a["kind"], a["hash"][:12], a["accepted"]))
'

echo
echo "-- reliability metrics"
api GET "/api/v1/metrics/reliability" | json '
import json as j
print(j.dumps(d, indent=2))
'

echo
echo "Open the console at $BASE/ and select run $RUN_ID to inspect the graph, lineage and artifacts."
