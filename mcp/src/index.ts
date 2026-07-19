#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { loadConfig } from "./config.js";
import { RelayLink } from "./relay.js";
import { buildTools, type Capabilities, type ToolDef } from "./tools.js";
import { pair } from "./pair.js";

/**
 * MCP server for MCPserved.
 *
 * Stdio transport, one device per process. Multiple devices would mean routing
 * every call by a target argument the model has to get right, and getting it
 * wrong means acting on the wrong phone — a failure with no recovery. Running a
 * second server entry is cheaper than a mistake of that shape.
 *
 * This process holds no authority. It carries sealed frames to a device that
 * decides, on its own, what to permit. Nothing here can widen what the user
 * granted, which is deliberate: this is the component sitting downstream of a
 * language model's output, and it is therefore the component least suited to
 * being the thing that says yes.
 */

async function main(): Promise<void> {
  if (process.argv[2] === "pair") {
    await pair();
    return;
  }

  const config = loadConfig();
  const link = new RelayLink(config);

  /**
   * Tool list, resolved once the device has been reached.
   *
   * Capabilities are queried lazily rather than at startup. An MCP host may
   * launch this process long before anything is asked of it, and waking a phone
   * to answer a question nobody posed is a rude way to spend someone's battery.
   */
  let tools: ToolDef[] | null = null;

  async function resolveTools(): Promise<ToolDef[]> {
    if (tools) return tools;

    const caps = (await link.send({ op: "capabilities" }, 15_000)) as
      | (Capabilities & { ok: boolean; error?: string })
      | { ok: false; error: string };

    if (!caps.ok) {
      // The device is unreachable. Advertise the unprivileged surface rather
      // than nothing: the model can still be told, by a tool that then fails,
      // what went wrong — an empty manifest tells it only that it has no hands.
      return buildTools({ caps: [], root: false, shizuku: false, a11y: false });
    }

    tools = buildTools(caps as Capabilities);
    return tools;
  }

  const server = new Server(
    { name: "mcpserved", version: "0.1.0" },
    { capabilities: { tools: {} } },
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => {
    const resolved = await resolveTools();
    return {
      tools: resolved.map((t) => ({
        name: t.name,
        description: t.description,
        inputSchema: t.inputSchema,
      })),
    };
  });

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const resolved = await resolveTools();
    const tool = resolved.find((t) => t.name === request.params.name);

    if (!tool) {
      return {
        content: [{ type: "text", text: `unknown tool: ${request.params.name}` }],
        isError: true,
      };
    }

    try {
      const text = await tool.handler(request.params.arguments ?? {}, link);
      // Device-level refusals arrive as ordinary text. They are outcomes the
      // model should reason about, not transport failures the host should hide.
      return { content: [{ type: "text", text }] };
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      return {
        content: [{ type: "text", text: `transport failure: ${message}` }],
        isError: true,
      };
    }
  });

  const shutdown = () => {
    link.close();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);

  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  // stderr, never stdout — stdout is the MCP channel and anything written there
  // that is not a protocol message corrupts the stream.
  console.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
