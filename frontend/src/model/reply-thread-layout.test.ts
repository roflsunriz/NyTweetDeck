import { expect, test } from "bun:test";
import { MAX_REPLY_THREAD_DEPTH, buildReplyThreadLayout } from "./reply-thread-layout";

interface ReplyNode {
  id: string;
  replyToPostId: string | null;
}

test("annotates a B-E-F branch without changing X response order", () => {
  const replies = [
    reply("B", "A"),
    reply("E", "B"),
    reply("F", "E"),
    reply("C", "A"),
    reply("D", "A"),
  ];

  const layout = buildReplyThreadLayout(replies, "A");

  expect(layout.map(({ reply: item }) => item.id)).toEqual(["B", "E", "F", "C", "D"]);
  expect(summary(layout)).toEqual([
    { id: "B", depth: 0, ancestorLines: [], last: false, leaf: false, capped: false },
    { id: "E", depth: 1, ancestorLines: [true], last: true, leaf: false, capped: false },
    {
      id: "F",
      depth: 2,
      ancestorLines: [true, false],
      last: true,
      leaf: true,
      capped: false,
    },
    { id: "C", depth: 0, ancestorLines: [], last: false, leaf: true, capped: false },
    { id: "D", depth: 0, ancestorLines: [], last: true, leaf: true, capped: false },
  ]);
});

test("keeps missing, self-referencing, and cyclic replies finite and visible", () => {
  const replies = [
    reply("direct", "A"),
    reply("orphan", "missing"),
    reply("self", "self"),
    reply("cycle-a", "cycle-b"),
    reply("cycle-b", "cycle-a"),
    reply("cycle-child", "cycle-a"),
  ];

  const layout = buildReplyThreadLayout(replies, "A");
  const byId = new Map(layout.map((item) => [item.reply.id, item]));

  expect(layout.map(({ reply: item }) => item.id)).toEqual(replies.map((item) => item.id));
  for (const id of ["direct", "orphan", "self", "cycle-a", "cycle-b"]) {
    expect(byId.get(id)?.depth).toBe(0);
  }
  expect(byId.get("cycle-child")?.depth).toBe(1);
  expect(byId.get("cycle-child")?.ancestorIds).toEqual(["cycle-a"]);
});

test("caps pathological depth and recomputes safely when a later page is appended", () => {
  const deepReplies = Array.from({ length: 50 }, (_, index) =>
    reply(`deep-${index}`, index === 0 ? "A" : `deep-${index - 1}`),
  );
  const deepLayout = buildReplyThreadLayout(deepReplies, "A");
  const deepest = deepLayout.at(-1);

  expect(deepest?.depth).toBe(MAX_REPLY_THREAD_DEPTH);
  expect(deepest?.ancestorIds).toHaveLength(MAX_REPLY_THREAD_DEPTH);
  expect(deepest?.depthCapped).toBe(true);

  const firstPage = [reply("B", "A"), reply("C", "A")];
  const appended = [...firstPage, reply("E", "B"), reply("F", "E")];
  const appendedLayout = buildReplyThreadLayout(appended, "A");
  expect(appendedLayout.map(({ reply: item }) => item.id)).toEqual(["B", "C", "E", "F"]);
  expect(appendedLayout[2]?.depth).toBe(1);
  expect(appendedLayout[3]?.depth).toBe(2);
});

function reply(id: string, replyToPostId: string | null): ReplyNode {
  return { id, replyToPostId };
}

function summary(layout: ReturnType<typeof buildReplyThreadLayout<ReplyNode>>) {
  return layout.map((item) => ({
    id: item.reply.id,
    depth: item.depth,
    ancestorLines: item.ancestorLines,
    last: item.isLastSibling,
    leaf: !item.hasChildren,
    capped: item.depthCapped,
  }));
}
