import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DeferredPanel } from "./DeferredPanel";

describe("DeferredPanel", () => {
  it("states that user-side realtime is not connected", () => {
    render(<DeferredPanel title="持仓" />);

    expect(screen.getByText("持仓")).toBeInTheDocument();
    expect(screen.getByText(/用户侧实时推送尚未接入/)).toBeInTheDocument();
  });
});
