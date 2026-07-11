import { beforeEach, describe, expect, it } from "vitest";
import { useWorkbench } from "../../state/workbenchStore";

const s = () => useWorkbench.getState();

beforeEach(() => {
  // Reset only the fields this test cares about.
  useWorkbench.setState({ rightEngine: "chat" });
});

describe("rightEngine / setRightEngine", () => {
  it("initial rightEngine is chat (default)", () => {
    expect(s().rightEngine).toBe("chat");
  });

  it("setRightEngine switches to claude", () => {
    s().setRightEngine("claude");
    expect(s().rightEngine).toBe("claude");
  });

  it("setRightEngine switches back from claude to chat", () => {
    s().setRightEngine("claude");
    s().setRightEngine("chat");
    expect(s().rightEngine).toBe("chat");
  });

  it("setRightEngine is idempotent — calling chat twice stays chat", () => {
    s().setRightEngine("chat");
    s().setRightEngine("chat");
    expect(s().rightEngine).toBe("chat");
  });

  it("setRightEngine does not reset other store fields", () => {
    useWorkbench.setState({ repoId: 42 });
    s().setRightEngine("claude");
    expect(s().repoId).toBe(42);
    expect(s().rightEngine).toBe("claude");
  });
});
