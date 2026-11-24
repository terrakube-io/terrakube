import { mapWithLimit } from "../mapWithLimit";

describe("mapWithLimit", () => {
  it("never runs more than the limit at once and keeps the order of the items", async () => {
    let running = 0;
    let peak = 0;
    const results = await mapWithLimit([1, 2, 3, 4, 5, 6], 2, async (n) => {
      running++;
      peak = Math.max(peak, running);
      await new Promise((resolve) => setTimeout(resolve, 5 * (7 - n)));
      running--;
      return n * 10;
    });

    expect(peak).toBe(2);
    expect(results.map((result) => (result.status === "fulfilled" ? result.value : null))).toEqual([
      10, 20, 30, 40, 50, 60,
    ]);
  });

  it("reports a failure where it happened and still runs the rest", async () => {
    const results = await mapWithLimit(["a", "b", "c"], 2, async (id) => {
      if (id === "b") throw new Error("nope");
      return id;
    });

    expect(results.map((result) => result.status)).toEqual(["fulfilled", "rejected", "fulfilled"]);
    expect((results[1] as PromiseRejectedResult).reason.message).toBe("nope");
  });

  it("returns nothing for nothing, and treats a limit below one as one", async () => {
    expect(await mapWithLimit([], 5, async () => 1)).toEqual([]);
    const results = await mapWithLimit([1, 2], 0, async (n) => n);
    expect(results).toHaveLength(2);
  });
});
