package com.kubernetes.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic Kubernetes MCP Server.
 *
 * Instead of a fixed list of tools, this server exposes a small set of generic
 * tools that proxy directly to the Kubernetes REST API:
 *
 *   discover_api_resources  — queries /api and /apis to enumerate every resource
 *                             type the cluster knows about (including CRDs).
 *   list_resources          — GET  /api(s)/{g}/{v}[/namespaces/{ns}]/{resource}
 *   get_resource            — GET  …/{resource}/{name}
 *   get_subresource         — GET  …/{resource}/{name}/{subresource}  (e.g. log)
 *   create_resource         — POST …/{resource}
 *   replace_resource        — PUT  …/{resource}/{name}
 *   patch_resource          — PATCH (JSON Merge Patch)
 *   apply_resource          — PATCH (Server-Side Apply)
 *   delete_resource         — DELETE …/{resource}/{name}
 *
 * High-level convenience tools (built on top of the generic layer):
 *   get_pod_logs            — rich pod log retrieval with container, previous, timestamps
 *   exec_in_pod             — execute a command inside a running container
 *   describe_resource       — aggregated view: resource + events (like kubectl describe)
 *   top_pods                — CPU/memory usage for pods (requires metrics-server)
 *   top_nodes               — CPU/memory usage for nodes (requires metrics-server)
 *   run_pod                 — quick pod creation from an image name
 *   get_cluster_info        — cluster version, API server URL, current context
 *   rollout_restart         — trigger a rolling restart of a Deployment/StatefulSet/DaemonSet
 *
 * Auth is handled by the Kubernetes Java client (kubeconfig / in-cluster SA token).
 * All HTTP calls reuse the OkHttpClient the SDK already configured with the right
 * TLS settings and auth interceptors — no kubectl binary required.
 */
public class DynamicMcpServer {

    private static final String SERVER_NAME      = "kubernetes-dynamic-mcp-server";
    private static final String SERVER_VERSION   = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintWriter  stdout;

    private OkHttpClient httpClient;
    private String       basePath;

    public DynamicMcpServer() {
        this.stdout = new PrintWriter(System.out, true);
    }

    public static void main(String[] args) {
        new DynamicMcpServer().run();
    }

    // ===== Startup & MCP stdio loop =====

    public void run() {
        try {
            ApiClient k8sClient = Config.defaultClient();
            basePath   = k8sClient.getBasePath();

            // Config.defaultClient() applies auth at the API-call level, not as an
            // OkHttp interceptor, so direct OkHttp requests would be anonymous.
            // Wrap the client with an explicit Bearer-token interceptor instead.
            String token = readBearerToken();
            OkHttpClient base = k8sClient.getHttpClient();
            httpClient = (token != null)
                    ? base.newBuilder()
                          .addInterceptor(chain -> chain.proceed(
                              chain.request().newBuilder()
                                   .header("Authorization", "Bearer " + token)
                                   .build()))
                          .build()
                    : base;

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    JsonNode request  = mapper.readTree(line);
                    JsonNode response = handleRequest(request);
                    if (response != null) stdout.println(response.toString());
                } catch (Exception e) {
                    sendError(null, -32700, "Parse error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    // ===== MCP Protocol Dispatch =====

    private JsonNode handleRequest(JsonNode request) {
        String  method = request.path("method").asText();
        JsonNode id    = request.get("id");
        JsonNode params = request.get("params");
        try {
            switch (method) {
                case "initialize":              return handleInitialize(id);
                case "notifications/initialized": return null;
                case "tools/list":              return handleToolsList(id);
                case "tools/call":              return handleToolsCall(id, params);
                case "ping":                    return createResult(id, mapper.createObjectNode());
                default:                        return createError(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            return createError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonNode handleInitialize(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps  = mapper.createObjectNode();
        caps.set("tools", mapper.createObjectNode().put("listChanged", false));
        result.set("capabilities", caps);
        ObjectNode info  = mapper.createObjectNode();
        info.put("name",    SERVER_NAME);
        info.put("version", SERVER_VERSION);
        result.set("serverInfo", info);
        return createResult(id, result);
    }

    // ===== Tool Definitions =====

    private JsonNode handleToolsList(JsonNode id) {
        ArrayNode tools = mapper.createArrayNode();

        tools.add(tool("discover_api_resources",
            "Enumerate every API resource type available in the cluster — built-ins AND custom " +
            "resources (CRDs). Returns group, version, resource (plural), kind, namespaced flag " +
            "and supported verbs. Call this first to discover valid values for the other tools.",
            new ObjectNode[0]));

        tools.add(tool("list_resources",
            "List Kubernetes resources of ANY type (pods, deployments, CRDs, …). " +
            "Use discover_api_resources to find the correct resource/group/version values.",
            param("resource",      "Plural resource name from discover_api_resources (e.g. pods, deployments, widgets)", true),
            param("group",         "API group (e.g. apps, batch, cert-manager.io). Omit or use 'core' for core resources (pods, services, nodes, …)", false),
            param("version",       "API version (e.g. v1, v1beta1). Defaults to v1", false),
            param("namespace",     "Namespace to list from. Omit for cluster-scoped resources or to list across all namespaces", false),
            param("labelSelector", "Label selector filter, e.g. app=nginx,tier=frontend", false),
            param("fieldSelector", "Field selector filter, e.g. status.phase=Running", false)));

        tools.add(tool("get_resource",
            "Get a specific Kubernetes resource by name. Returns the full resource definition as JSON.",
            param("resource",  "Plural resource name (e.g. pods, deployments)", true),
            param("name",      "Resource name", true),
            param("group",     "API group. Omit for core resources", false),
            param("version",   "API version. Defaults to v1", false),
            param("namespace", "Namespace for namespaced resources", false)));

        tools.add(tool("get_subresource",
            "Fetch a subresource of a Kubernetes resource (e.g. pod logs, pod status, deployment scale). " +
            "Use subresource='log' to get pod log output.",
            param("resource",    "Plural resource name (e.g. pods)", true),
            param("name",        "Resource name", true),
            param("subresource", "Subresource name: log | status | scale | exec | …", true),
            param("group",       "API group. Omit for core resources", false),
            param("version",     "API version. Defaults to v1", false),
            param("namespace",   "Namespace", false),
            paramInt("tailLines", "For log subresource: number of tail lines to return", false)));

        tools.add(tool("create_resource",
            "Create a new Kubernetes resource from a JSON manifest body.",
            param("resource",  "Plural resource name", true),
            param("body",      "Full resource manifest as a JSON string (must include apiVersion, kind, metadata)", true),
            param("group",     "API group. Omit for core resources", false),
            param("version",   "API version. Defaults to v1", false),
            param("namespace", "Namespace for namespaced resources", false)));

        tools.add(tool("replace_resource",
            "Replace (full update / kubectl replace) an existing Kubernetes resource with a new manifest. " +
            "The body must include the current resourceVersion in metadata.",
            param("resource",  "Plural resource name", true),
            param("name",      "Resource name", true),
            param("body",      "Complete replacement manifest as JSON", true),
            param("group",     "API group. Omit for core resources", false),
            param("version",   "API version. Defaults to v1", false),
            param("namespace", "Namespace", false)));

        tools.add(tool("patch_resource",
            "Patch a Kubernetes resource using JSON Merge Patch (RFC 7396). " +
            "Provide only the fields you want to change.",
            param("resource",  "Plural resource name", true),
            param("name",      "Resource name", true),
            param("patch",     "JSON merge-patch document (only fields to change)", true),
            param("group",     "API group. Omit for core resources", false),
            param("version",   "API version. Defaults to v1", false),
            param("namespace", "Namespace", false)));

        tools.add(tool("apply_resource",
            "Server-side apply a resource manifest (kubectl apply --server-side). " +
            "Creates the resource if it does not exist; patches it if it does.",
            param("resource",      "Plural resource name", true),
            param("name",          "Resource name", true),
            param("body",          "Full resource manifest as JSON", true),
            param("group",         "API group. Omit for core resources", false),
            param("version",       "API version. Defaults to v1", false),
            param("namespace",     "Namespace", false),
            param("fieldManager",  "Field manager identifier (default: dynamic-mcp)", false)));

        tools.add(tool("delete_resource",
            "Delete a Kubernetes resource by name.",
            param("resource",  "Plural resource name", true),
            param("name",      "Resource name", true),
            param("group",     "API group. Omit for core resources", false),
            param("version",   "API version. Defaults to v1", false),
            param("namespace", "Namespace", false)));

        // ===== High-Level Convenience Tools =====

        tools.add(tool("get_pod_logs",
            "Get logs from a pod with advanced options. Supports multi-container pods, " +
            "previous instance logs, timestamps, and time-based filtering.",
            param("name",           "Pod name", true),
            param("namespace",      "Namespace (required)", true),
            param("container",      "Container name (required for multi-container pods)", false),
            paramBool("previous",   "Return logs from the previous terminated container instance", false),
            paramBool("timestamps", "Prefix each log line with its RFC3339 timestamp", false),
            paramInt("tailLines",   "Number of lines from the end to return", false),
            paramInt("sinceSeconds","Only return logs newer than this many seconds", false)));

        tools.add(tool("exec_in_pod",
            "Execute a command inside a running pod container. Returns combined stdout/stderr output. " +
            "Example: exec_in_pod(name='nginx', namespace='default', command='ls -la /app')",
            param("name",      "Pod name", true),
            param("namespace", "Namespace (required)", true),
            param("command",   "Command to execute (e.g. 'ls -la /app' or 'cat /etc/hosts')", true),
            param("container", "Container name (required for multi-container pods)", false)));

        tools.add(tool("describe_resource",
            "Get a comprehensive summary of a resource similar to 'kubectl describe'. " +
            "Returns the resource definition plus related Events, providing a complete " +
            "diagnostic picture in one call.",
            param("resource",  "Plural resource name (e.g. pods, deployments, services)", true),
            param("name",      "Resource name", true),
            param("group",     "API group. Omit for core resources", false),
            param("version",   "API version. Defaults to v1", false),
            param("namespace", "Namespace", false)));

        tools.add(tool("top_pods",
            "Get CPU and memory usage metrics for pods (requires metrics-server installed in cluster). " +
            "Returns actual resource consumption — useful for identifying resource-hungry pods.",
            param("namespace", "Namespace to query. Omit to get metrics across all namespaces", false)));

        tools.add(tool("top_nodes",
            "Get CPU and memory usage metrics for all nodes (requires metrics-server installed in cluster). " +
            "Shows actual resource consumption per node — useful for capacity planning.",
            new ObjectNode[0]));

        tools.add(tool("run_pod",
            "Quickly create and run a pod from a container image (like 'kubectl run'). " +
            "Automatically constructs the pod manifest — no need to provide full JSON.",
            param("name",      "Pod name", true),
            param("image",     "Container image (e.g. nginx, busybox, redis:7)", true),
            param("namespace", "Namespace (defaults to 'default')", false),
            param("command",   "Override command (e.g. 'sleep 3600' or 'sh -c echo hello')", false),
            paramInt("port",   "Container port to expose", false),
            paramBool("expose","Also create a ClusterIP Service for this pod", false)));

        tools.add(tool("get_cluster_info",
            "Get cluster connection information: Kubernetes version, API server URL, " +
            "platform, and current context name.",
            new ObjectNode[0]));

        tools.add(tool("rollout_restart",
            "Trigger a rolling restart of a Deployment, StatefulSet, or DaemonSet " +
            "(equivalent to 'kubectl rollout restart'). Patches the pod template " +
            "with a restart annotation to force new pods.",
            param("name",      "Workload name", true),
            param("namespace", "Namespace (required)", true),
            param("kind",      "Workload kind: Deployment (default), StatefulSet, or DaemonSet", false)));

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return createResult(id, result);
    }

    // ===== Tool Dispatch =====

    private JsonNode handleToolsCall(JsonNode id, JsonNode params) {
        String  name = params.path("name").asText();
        JsonNode args = params.get("arguments");
        try {
            JsonNode result = executeTool(name, args);
            return toolResult(id, result);
        } catch (Exception e) {
            return toolError(id, e.getMessage());
        }
    }

    private JsonNode executeTool(String name, JsonNode args) throws Exception {
        String resource  = str(args, "resource");
        String resName   = str(args, "name");
        String group     = str(args, "group");
        String version   = str(args, "version");
        String namespace = str(args, "namespace");

        switch (name) {
            case "discover_api_resources":
                return discoverApiResources();

            case "list_resources":
                return listResources(group, version, resource, namespace,
                        str(args, "labelSelector"), str(args, "fieldSelector"));

            case "get_resource":
                return getResource(group, version, resource, namespace, resName);

            case "get_subresource":
                return getSubresource(group, version, resource, namespace, resName,
                        str(args, "subresource"), intArg(args, "tailLines"));

            case "create_resource":
                return apiCall("POST",
                        buildApiPath(group, version, resource, namespace, null),
                        str(args, "body"), "application/json", null);

            case "replace_resource":
                return apiCall("PUT",
                        buildApiPath(group, version, resource, namespace, resName),
                        str(args, "body"), "application/json", null);

            case "patch_resource":
                return apiCall("PATCH",
                        buildApiPath(group, version, resource, namespace, resName),
                        str(args, "patch"), "application/merge-patch+json", null);

            case "apply_resource": {
                String fm = str(args, "fieldManager");
                if (fm == null || fm.isEmpty()) fm = "dynamic-mcp";
                Map<String, String> qp = new HashMap<>();
                qp.put("fieldManager", fm);
                qp.put("force", "true");
                return apiCall("PATCH",
                        buildApiPath(group, version, resource, namespace, resName),
                        str(args, "body"), "application/apply-patch+yaml", qp);
            }

            case "delete_resource":
                return apiCall("DELETE",
                        buildApiPath(group, version, resource, namespace, resName),
                        null, null, null);

            // ===== High-Level Convenience Tools =====

            case "get_pod_logs":
                return getPodLogs(args);

            case "exec_in_pod":
                return execInPod(args);

            case "describe_resource":
                return describeResource(group, version, resource, namespace, resName);

            case "top_pods":
                return topPods(namespace);

            case "top_nodes":
                return topNodes();

            case "run_pod":
                return runPod(args);

            case "get_cluster_info":
                return getClusterInfo();

            case "rollout_restart":
                return rolloutRestart(args);

            default:
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    // ===== Tool Implementations =====

    /**
     * Walks /api (core group) and /apis (all named groups), calling the resource
     * list endpoint for each group/version to build a full catalogue of everything
     * the cluster knows about — built-ins, aggregated APIs, and CRDs alike.
     */
    private JsonNode discoverApiResources() throws Exception {
        ArrayNode all = mapper.createArrayNode();

        // Core resources: GET /api  →  {"versions": ["v1", ...]}
        JsonNode coreVersions = fetchJson("/api");
        JsonNode versions = coreVersions.get("versions");
        if (versions != null) {
            for (JsonNode v : versions) {
                JsonNode resourceList = fetchJson("/api/" + v.asText());
                addResources(all, "", v.asText(), resourceList.get("resources"));
            }
        }

        // Named groups: GET /apis  →  APIGroupList
        JsonNode groups = fetchJson("/apis").get("groups");
        if (groups != null) {
            for (JsonNode group : groups) {
                JsonNode preferred = group.get("preferredVersion");
                if (preferred == null) continue;
                String groupName = group.get("name").asText();
                String ver       = preferred.get("version").asText();
                JsonNode resourceList = fetchJson("/apis/" + groupName + "/" + ver);
                addResources(all, groupName, ver, resourceList.get("resources"));
            }
        }

        return all;
    }

    /** Append non-subresource entries from an APIResourceList to the result array. */
    private void addResources(ArrayNode result, String group, String version, JsonNode resources) {
        if (resources == null) return;
        for (JsonNode r : resources) {
            String rName = r.path("name").asText();
            if (rName.contains("/")) continue;  // skip subresources (pods/log, etc.)
            ObjectNode entry = mapper.createObjectNode();
            entry.put("group",      group.isEmpty() ? "core" : group);
            entry.put("version",    version);
            entry.put("resource",   rName);
            entry.put("kind",       r.path("kind").asText());
            entry.put("namespaced", r.path("namespaced").asBoolean());
            if (r.get("verbs") != null) entry.set("verbs", r.get("verbs"));
            result.add(entry);
        }
    }

    private JsonNode listResources(String group, String version, String resource,
                                    String namespace, String labelSelector, String fieldSelector) throws Exception {
        Map<String, String> qp = new HashMap<>();
        if (labelSelector != null && !labelSelector.isEmpty()) qp.put("labelSelector", labelSelector);
        if (fieldSelector  != null && !fieldSelector.isEmpty()) qp.put("fieldSelector",  fieldSelector);
        String path = buildApiPath(group, version, resource, namespace, null);
        return fetchJson(path, qp.isEmpty() ? null : qp);
    }

    private JsonNode getResource(String group, String version, String resource,
                                  String namespace, String name) throws Exception {
        return fetchJson(buildApiPath(group, version, resource, namespace, name), null);
    }

    private JsonNode getSubresource(String group, String version, String resource,
                                     String namespace, String name,
                                     String subresource, Integer tailLines) throws Exception {
        Map<String, String> qp = new HashMap<>();
        if (tailLines != null) qp.put("tailLines", tailLines.toString());

        String path = buildApiPath(group, version, resource, namespace, name) + "/" + subresource;
        String raw  = rawGet(path, qp.isEmpty() ? null : qp);

        // Log subresource returns plain text — wrap it for the AI
        if ("log".equals(subresource)) {
            ObjectNode node = mapper.createObjectNode();
            node.put("logs", raw);
            return node;
        }
        return parseResponse(raw);
    }

    // ===== High-Level Tool Implementations =====

    private JsonNode getPodLogs(JsonNode args) throws Exception {
        String name      = str(args, "name");
        String namespace = str(args, "namespace");
        if (name == null || namespace == null)
            throw new IllegalArgumentException("name and namespace are required");

        Map<String, String> qp = new HashMap<>();
        String container = str(args, "container");
        if (container != null && !container.isEmpty()) qp.put("container", container);
        if (boolArg(args, "previous"))   qp.put("previous",   "true");
        if (boolArg(args, "timestamps")) qp.put("timestamps", "true");
        Integer tailLines    = intArg(args, "tailLines");
        Integer sinceSeconds = intArg(args, "sinceSeconds");
        if (tailLines != null)    qp.put("tailLines",    tailLines.toString());
        if (sinceSeconds != null) qp.put("sinceSeconds", sinceSeconds.toString());

        String path = "/api/v1/namespaces/" + namespace + "/pods/" + name + "/log";
        String raw  = rawGet(path, qp.isEmpty() ? null : qp);
        ObjectNode node = mapper.createObjectNode();
        node.put("pod",       name);
        node.put("namespace", namespace);
        if (container != null) node.put("container", container);
        node.put("logs",      raw);
        return node;
    }

    private JsonNode execInPod(JsonNode args) throws Exception {
        String name      = str(args, "name");
        String namespace = str(args, "namespace");
        String command   = str(args, "command");
        String container = str(args, "container");
        if (name == null || namespace == null || command == null)
            throw new IllegalArgumentException("name, namespace, and command are required");

        // Build exec URL with command split by spaces
        // Kubernetes API expects each arg as separate 'command' query param
        String path = "/api/v1/namespaces/" + namespace + "/pods/" + name + "/exec";
        HttpUrl.Builder urlBuilder = HttpUrl.parse(basePath + path).newBuilder();
        String[] parts = command.split("\\s+");
        for (String part : parts) urlBuilder.addQueryParameter("command", part);
        urlBuilder.addQueryParameter("stdout", "true");
        urlBuilder.addQueryParameter("stderr", "true");
        if (container != null && !container.isEmpty())
            urlBuilder.addQueryParameter("container", container);

        Request request = new Request.Builder().url(urlBuilder.build())
                .post(RequestBody.create("", MediaType.parse("application/json")))
                .build();
        try (Response resp = httpClient.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            ObjectNode node = mapper.createObjectNode();
            node.put("pod",       name);
            node.put("namespace", namespace);
            node.put("command",   command);
            node.put("exitCode",  resp.isSuccessful() ? 0 : resp.code());
            node.put("output",    body);
            return node;
        }
    }

    private JsonNode describeResource(String group, String version, String resource,
                                       String namespace, String name) throws Exception {
        // 1. Fetch the resource itself
        JsonNode obj = fetchJson(buildApiPath(group, version, resource, namespace, name), null);

        // 2. Fetch related Events filtered by involvedObject
        Map<String, String> evQp = new HashMap<>();
        String fieldSel = "involvedObject.name=" + name;
        if (namespace != null && !namespace.isEmpty())
            fieldSel += ",involvedObject.namespace=" + namespace;
        evQp.put("fieldSelector", fieldSel);
        String evPath = namespace != null && !namespace.isEmpty()
                ? "/api/v1/namespaces/" + namespace + "/events"
                : "/api/v1/events";
        JsonNode events = fetchJson(evPath, evQp);

        // 3. Combine into a single result
        ObjectNode result = mapper.createObjectNode();
        result.set("resource", obj);
        result.set("events",   events.has("items") ? events.get("items") : mapper.createArrayNode());
        return result;
    }

    private JsonNode topPods(String namespace) throws Exception {
        String path = namespace != null && !namespace.isEmpty()
                ? "/apis/metrics.k8s.io/v1beta1/namespaces/" + namespace + "/pods"
                : "/apis/metrics.k8s.io/v1beta1/pods";
        return fetchJson(path, null);
    }

    private JsonNode topNodes() throws Exception {
        return fetchJson("/apis/metrics.k8s.io/v1beta1/nodes", null);
    }

    private JsonNode runPod(JsonNode args) throws Exception {
        String name  = str(args, "name");
        String image = str(args, "image");
        if (name == null || image == null)
            throw new IllegalArgumentException("name and image are required");

        String  ns      = str(args, "namespace");
        if (ns == null || ns.isEmpty()) ns = "default";
        String  command = str(args, "command");
        Integer port    = intArg(args, "port");
        boolean expose  = boolArg(args, "expose");

        // Build container spec
        ObjectNode container = mapper.createObjectNode();
        container.put("name",  name);
        container.put("image", image);

        if (command != null && !command.isEmpty()) {
            ArrayNode cmdArr = mapper.createArrayNode();
            for (String p : command.split("\\s+")) cmdArr.add(p);
            container.set("command", cmdArr);
        }

        if (port != null) {
            ArrayNode ports = mapper.createArrayNode();
            ObjectNode cp   = mapper.createObjectNode();
            cp.put("containerPort", port);
            ports.add(cp);
            container.set("ports", ports);
        }

        // Build pod manifest
        ObjectNode pod = mapper.createObjectNode();
        pod.put("apiVersion", "v1");
        pod.put("kind",       "Pod");
        ObjectNode meta = mapper.createObjectNode();
        meta.put("name",      name);
        meta.put("namespace", ns);
        ObjectNode labels = mapper.createObjectNode();
        labels.put("run", name);
        meta.set("labels", labels);
        pod.set("metadata", meta);
        ObjectNode spec = mapper.createObjectNode();
        ArrayNode containers = mapper.createArrayNode();
        containers.add(container);
        spec.set("containers", containers);
        spec.put("restartPolicy", "Always");
        pod.set("spec", spec);

        // Create the pod
        JsonNode podResult = apiCall("POST",
                "/api/v1/namespaces/" + ns + "/pods",
                mapper.writeValueAsString(pod), "application/json", null);

        // Optionally create a ClusterIP service
        if (expose && port != null) {
            ObjectNode svc = mapper.createObjectNode();
            svc.put("apiVersion", "v1");
            svc.put("kind",       "Service");
            ObjectNode svcMeta = mapper.createObjectNode();
            svcMeta.put("name",      name);
            svcMeta.put("namespace", ns);
            svc.set("metadata", svcMeta);
            ObjectNode svcSpec = mapper.createObjectNode();
            ObjectNode selector = mapper.createObjectNode();
            selector.put("run", name);
            svcSpec.set("selector", selector);
            ArrayNode svcPorts = mapper.createArrayNode();
            ObjectNode sp      = mapper.createObjectNode();
            sp.put("port",       port);
            sp.put("targetPort", port);
            sp.put("protocol",   "TCP");
            svcPorts.add(sp);
            svcSpec.set("ports", svcPorts);
            svc.set("spec", svcSpec);

            JsonNode svcResult = apiCall("POST",
                    "/api/v1/namespaces/" + ns + "/services",
                    mapper.writeValueAsString(svc), "application/json", null);

            ObjectNode combined = mapper.createObjectNode();
            combined.set("pod",     podResult);
            combined.set("service", svcResult);
            return combined;
        }

        return podResult;
    }

    private JsonNode getClusterInfo() throws Exception {
        // Kubernetes version info
        JsonNode versionInfo = fetchJson("/version", null);

        ObjectNode result = mapper.createObjectNode();
        result.put("apiServer",   basePath);
        result.put("gitVersion",  versionInfo.path("gitVersion").asText(""));
        result.put("platform",    versionInfo.path("platform").asText(""));
        result.put("goVersion",   versionInfo.path("goVersion").asText(""));
        result.put("buildDate",   versionInfo.path("buildDate").asText(""));
        result.put("major",       versionInfo.path("major").asText(""));
        result.put("minor",       versionInfo.path("minor").asText(""));
        return result;
    }

    private JsonNode rolloutRestart(JsonNode args) throws Exception {
        String name = str(args, "name");
        String ns   = str(args, "namespace");
        String kind = str(args, "kind");
        if (name == null || ns == null)
            throw new IllegalArgumentException("name and namespace are required");

        // Determine the resource type and group
        String resource;
        if (kind == null || kind.isEmpty() || "Deployment".equalsIgnoreCase(kind))
            resource = "deployments";
        else if ("StatefulSet".equalsIgnoreCase(kind))
            resource = "statefulsets";
        else if ("DaemonSet".equalsIgnoreCase(kind))
            resource = "daemonsets";
        else
            throw new IllegalArgumentException("kind must be Deployment, StatefulSet, or DaemonSet");

        // The same technique kubectl uses: patch pod template annotation with a timestamp
        String timestamp = Instant.now().toString();
        String patch = mapper.writeValueAsString(
            mapper.createObjectNode().set("spec",
                mapper.createObjectNode().set("template",
                    mapper.createObjectNode().set("metadata",
                        mapper.createObjectNode().set("annotations",
                            mapper.createObjectNode().put("kubectl.kubernetes.io/restartedAt", timestamp))))));

        return apiCall("PATCH",
                buildApiPath("apps", "v1", resource, ns, name),
                patch, "application/strategic-merge-patch+json", null);
    }

    // ===== Generic HTTP layer =====

    /**
     * Execute any Kubernetes API call (POST / PUT / PATCH / DELETE).
     * Re-uses the OkHttpClient that the K8s SDK already configured with
     * the right TLS certs and auth interceptors.
     */
    private JsonNode apiCall(String method, String path, String body,
                              String contentType, Map<String, String> queryParams) throws IOException {
        HttpUrl url = buildUrl(path, queryParams);
        RequestBody requestBody = null;
        if (body != null && !body.isEmpty()) {
            requestBody = RequestBody.create(body, MediaType.parse(contentType));
        }
        RequestBody emptyJson = RequestBody.create("{}", MediaType.parse("application/json"));

        Request.Builder rb = new Request.Builder().url(url);
        switch (method) {
            case "GET":    rb.get(); break;
            case "POST":   rb.post(requestBody   != null ? requestBody : emptyJson); break;
            case "PUT":    rb.put(requestBody    != null ? requestBody : emptyJson); break;
            case "PATCH":  rb.patch(requestBody  != null ? requestBody : emptyJson); break;
            case "DELETE": rb.delete(requestBody); break;
            default: throw new IllegalArgumentException("Unknown HTTP method: " + method);
        }

        try (Response resp = httpClient.newCall(rb.build()).execute()) {
            String responseBody = resp.body() != null ? resp.body().string() : "{}";
            return parseResponse(responseBody);
        }
    }

    private JsonNode fetchJson(String path) throws IOException {
        return parseResponse(rawGet(path, null));
    }

    private JsonNode fetchJson(String path, Map<String, String> queryParams) throws IOException {
        return parseResponse(rawGet(path, queryParams));
    }

    private String rawGet(String path, Map<String, String> queryParams) throws IOException {
        Request request = new Request.Builder().url(buildUrl(path, queryParams)).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private HttpUrl buildUrl(String path, Map<String, String> queryParams) {
        HttpUrl parsed = HttpUrl.parse(basePath + path);
        if (parsed == null) throw new IllegalArgumentException("Invalid URL: " + basePath + path);
        HttpUrl.Builder builder = parsed.newBuilder();
        if (queryParams != null) queryParams.forEach(builder::addQueryParameter);
        return builder.build();
    }

    /**
     * Try to parse the response body as JSON.
     * If it is plain text (e.g. older API versions, exec), wrap it in {text: "..."}.
     */
    private JsonNode parseResponse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            ObjectNode node = mapper.createObjectNode();
            node.put("text", raw);
            return node;
        }
    }

    // ===== Kubernetes Path Builder =====

    /**
     * Constructs the correct Kubernetes REST path based on the resource's API group:
     *   core (group="" or "core")  →  /api/{version}[/namespaces/{ns}]/{resource}[/{name}]
     *   named group                →  /apis/{group}/{version}[/namespaces/{ns}]/{resource}[/{name}]
     */
    private String buildApiPath(String group, String version, String resource,
                                  String namespace, String name) {
        boolean isCore = (group == null || group.isEmpty() || "core".equalsIgnoreCase(group));
        String  ver    = (version == null || version.isEmpty()) ? "v1" : version;

        StringBuilder sb = new StringBuilder();
        if (isCore) {
            sb.append("/api/").append(ver);
        } else {
            sb.append("/apis/").append(group).append("/").append(ver);
        }
        if (namespace != null && !namespace.isEmpty()) {
            sb.append("/namespaces/").append(namespace);
        }
        sb.append("/").append(resource);
        if (name != null && !name.isEmpty()) {
            sb.append("/").append(name);
        }
        return sb.toString();
    }

    // ===== Tool Schema Helpers =====

    private ObjectNode tool(String name, String desc, ObjectNode... params) {
        ObjectNode t      = mapper.createObjectNode();
        t.put("name",        name);
        t.put("description", desc);
        ObjectNode schema  = mapper.createObjectNode();
        schema.put("type",  "object");
        ObjectNode props   = mapper.createObjectNode();
        ArrayNode  req     = mapper.createArrayNode();
        for (ObjectNode p : params) {
            String pname = p.get("name").asText();
            props.set(pname, p.get("schema"));
            if (p.get("required").asBoolean()) req.add(pname);
        }
        schema.set("properties", props);
        if (req.size() > 0) schema.set("required", req);
        t.set("inputSchema", schema);
        return t;
    }

    private ObjectNode param(String name, String desc, boolean req) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name",     name);
        p.put("required", req);
        ObjectNode s = mapper.createObjectNode();
        s.put("type",        "string");
        s.put("description", desc);
        p.set("schema", s);
        return p;
    }

    private ObjectNode paramInt(String name, String desc, boolean req) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name",     name);
        p.put("required", req);
        ObjectNode s = mapper.createObjectNode();
        s.put("type",        "integer");
        s.put("description", desc);
        p.set("schema", s);
        return p;
    }

    // ===== MCP JSON-RPC Helpers =====

    private JsonNode createResult(JsonNode id, JsonNode result) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id",      id);
        r.set("result",  result);
        return r;
    }

    private JsonNode createError(JsonNode id, int code, String msg) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode e = mapper.createObjectNode();
        e.put("code",    code);
        e.put("message", msg);
        r.set("error", e);
        return r;
    }

    private void sendError(JsonNode id, int code, String msg) {
        stdout.println(createError(id, code, msg).toString());
    }

    private JsonNode toolResult(JsonNode id, JsonNode result) {
        ObjectNode r       = mapper.createObjectNode();
        r.put("jsonrpc",     "2.0");
        r.set("id",          id);
        ObjectNode res     = mapper.createObjectNode();
        ArrayNode  content = mapper.createArrayNode();
        ObjectNode txt     = mapper.createObjectNode();
        txt.put("type", "text");
        try {
            txt.put("text", mapper.writeValueAsString(result));
        } catch (Exception e) {
            txt.put("text", result.toString());
        }
        content.add(txt);
        res.set("content",  content);
        res.put("isError",  false);
        r.set("result",     res);
        return r;
    }

    private JsonNode toolError(JsonNode id, String msg) {
        ObjectNode r       = mapper.createObjectNode();
        r.put("jsonrpc",     "2.0");
        r.set("id",          id);
        ObjectNode res     = mapper.createObjectNode();
        ArrayNode  content = mapper.createArrayNode();
        ObjectNode txt     = mapper.createObjectNode();
        txt.put("type", "text");
        txt.put("text", msg);
        content.add(txt);
        res.set("content",  content);
        res.put("isError",  true);
        r.set("result",     res);
        return r;
    }

    private String  str(JsonNode a, String k) { return a != null && a.has(k) ? a.get(k).asText()  : null; }
    private Integer intArg(JsonNode a, String k) { return a != null && a.has(k) ? a.get(k).asInt() : null; }
    private boolean boolArg(JsonNode a, String k) { return a != null && a.has(k) && a.get(k).asBoolean(); }

    private ObjectNode paramBool(String name, String desc, boolean req) {
        ObjectNode p = mapper.createObjectNode();
        p.put("name",     name);
        p.put("required", req);
        ObjectNode s = mapper.createObjectNode();
        s.put("type",        "boolean");
        s.put("description", desc);
        p.set("schema", s);
        return p;
    }

    /**
     * Reads the bearer token for the current kubeconfig context.
     * Config.defaultClient() applies auth at the API-call level (not as an OkHttp
     * network interceptor), so direct OkHttp calls go out without the Authorization
     * header.  We read the token ourselves and attach it via an interceptor instead.
     */
    @SuppressWarnings("unchecked")
    private String readBearerToken() {
        try {
            String kubeconfigPath = System.getenv("KUBECONFIG");
            if (kubeconfigPath == null)
                kubeconfigPath = System.getProperty("user.home")
                        + File.separator + ".kube" + File.separator + "config";
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> config;
            try (FileReader reader = new FileReader(kubeconfigPath)) {
                config = yaml.load(reader);
            }
            String currentContext = (String) config.get("current-context");
            // Resolve the user name from the current context
            String userName = null;
            List<Map<String, Object>> contexts =
                    (List<Map<String, Object>>) config.get("contexts");
            if (contexts != null) {
                for (Map<String, Object> entry : contexts) {
                    if (currentContext.equals(entry.get("name"))) {
                        Map<String, Object> ctx = (Map<String, Object>) entry.get("context");
                        if (ctx != null) userName = (String) ctx.get("user");
                        break;
                    }
                }
            }
            // Extract the token from the user entry
            if (userName != null) {
                List<Map<String, Object>> users =
                        (List<Map<String, Object>>) config.get("users");
                if (users != null) {
                    for (Map<String, Object> entry : users) {
                        if (userName.equals(entry.get("name"))) {
                            Map<String, Object> user = (Map<String, Object>) entry.get("user");
                            if (user != null) return (String) user.get("token");
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not read bearer token from kubeconfig: "
                    + e.getMessage());
        }
        return null;
    }
}
