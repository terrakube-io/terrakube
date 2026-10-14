import { GRAPH_NODE_LIMIT, indexConnections, limitGraphNodes, neighborhood } from "../utils/graphView";

it("focuses a 1,000-workspace chain without leaking beyond the requested distance", () => {
  const edges = Array.from({ length: 999 }, (_, i) => ({ source: `w${i}`, target: `w${i + 1}` }));
  const index = indexConnections(edges);
  expect([...neighborhood(index, ["w500"], 2)].sort()).toEqual(["w498", "w499", "w500", "w501", "w502"]);
  expect([...neighborhood(index, ["w500"], 2, "upstream")].sort()).toEqual(["w498", "w499", "w500"]);
  expect([...neighborhood(index, ["w500"], 2, "downstream")].sort()).toEqual(["w500", "w501", "w502"]);
});

it("counts each node once across diamonds and cycles", () => {
  const index = indexConnections([
    { source: "a", target: "b" },
    { source: "a", target: "c" },
    { source: "b", target: "d" },
    { source: "c", target: "d" },
    { source: "d", target: "a" },
  ]);
  expect([...neighborhood(index, ["a"], 50)].sort()).toEqual(["a", "b", "c", "d"]);
});

it("keeps a late-selected workspace in a bounded overview and expands only on request", () => {
  const nodes = Array.from({ length: 1000 }, (_, i) => ({ id: `w${i}` }));
  const view = limitGraphNodes(nodes, "w999");
  expect(view).toHaveLength(GRAPH_NODE_LIMIT);
  expect(view[0].id).toBe("w999");
  expect(new Set(view.map((node) => node.id)).size).toBe(GRAPH_NODE_LIMIT);
  expect(limitGraphNodes(nodes, "w999", true)).toHaveLength(1000);
  expect(nodes[0].id).toBe("w0");
});

it("bounds an explicitly expanded organization and keeps its selected workspace", () => {
  const nodes = Array.from({ length: 5000 }, (_, i) => ({ id: `w${i}` }));
  const view = limitGraphNodes(nodes, "w4999", true);
  expect(view).toHaveLength(1000);
  expect(view[0].id).toBe("w4999");
});
