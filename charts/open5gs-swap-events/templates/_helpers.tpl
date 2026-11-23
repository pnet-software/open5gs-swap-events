{{- define "swap-events.name" -}}
{{- default (.Chart.Name | trimSuffix "-chart") .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "swap-events.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := include "swap-events.name" . }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "swap-events.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "swap-events.selectorLabels" -}}
app.kubernetes.io/name: {{ include "swap-events.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "swap-events.labels" -}}
helm.sh/chart: {{ include "swap-events.chart" . }}
{{ include "swap-events.selectorLabels" . }}
app.kubernetes.io/version: {{ include "swap-events.imageTag" . | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "swap-events.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "swap-events.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "swap-events.imageTag" -}}
{{- default .Chart.AppVersion .Values.image.tag }}
{{- end }}

{{- define "swap-events.image" -}}
{{- printf "%s:%s" .Values.image.repository (include "swap-events.imageTag" .) }}
{{- end }}

{{/*
Fails the render when nothing provides MONGODB_URI.
*/}}
{{- define "swap-events.validate" -}}
{{- $fromExtraEnv := false }}
{{- range .Values.extraEnv }}
{{- if eq .name "MONGODB_URI" }}{{- $fromExtraEnv = true }}{{- end }}
{{- end }}
{{- if not (or .Values.mongodb.uri $fromExtraEnv .Values.extraEnvFrom) }}
{{- fail "mongodb.uri is required (or set MONGODB_URI through extraEnv or extraEnvFrom)" }}
{{- end }}
{{- end }}
