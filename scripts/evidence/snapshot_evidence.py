import sys
import os
import time
import re
from urllib.parse import quote
from playwright.sync_api import sync_playwright


def normalized_scenario_id(value):
    value = os.path.splitext(os.path.basename(value))[0].lower()
    return re.sub(r"[^a-z0-9]", "", value)


def snapshot_scenario(scenario_id, output_path="evidence/snapshot.png"):
    """
    Captures a high-resolution screenshot of a scenario card in workbench-v2.
    """
    print(f"📝 Selecting scenario: {scenario_id}")
    
    # Standard 1:1 Aspect Ratio for Social (Farcaster/X)
    viewport = {"width": 1200, "height": 1200}
    
    base_url = os.environ.get("CLERK_BASE_URL", "http://localhost:7777").rstrip("/")
    notebook_route = os.environ.get("CLERK_NOTEBOOK_ROUTE", "/notebooks/workbench_v2.clj")
    clerk_url = f"{base_url}/{quote(notebook_route.lstrip('/'), safe='/')}"
    
    print("📸 Initializing Snapshot Engine")
    print(f"🔗 Accessing URL: {clerk_url}")
    
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport=viewport, device_scale_factor=2)
        page = context.new_page()
        
        # Navigate to the Clerk notebook
        page.goto(clerk_url, wait_until="commit", timeout=60000)

        print("⏳ Waiting for the pinned scenario catalogue (max 60s)...")
        root = page.locator("details.scenario-snapshot-root")
        root.wait_for(state="visible", timeout=60000)
        if not root.evaluate("element => element.open"):
            root.locator(":scope > summary").click()
        wanted = normalized_scenario_id(scenario_id)
        matches = [candidate for candidate in page.locator(".scenario-snapshot-item").all()
                   if normalized_scenario_id(candidate.get_attribute("data-scenario-id")) == wanted]
        if not matches:
            raise RuntimeError(f"Scenario {scenario_id!r} is not present in the pinned scenario catalogue")
        if len(matches) > 1:
            ids = [candidate.get_attribute("data-scenario-id") for candidate in matches]
            raise RuntimeError(f"Scenario ID {scenario_id!r} is ambiguous after normalization: {ids}")
        card = matches[0]
        if not card.evaluate("element => element.open"):
            card.locator("summary").click()
        card.wait_for(state="visible", timeout=60000)
        if normalized_scenario_id(card.get_attribute("data-scenario-id")) != wanted:
            raise RuntimeError(f"Workbench captured a different scenario than {scenario_id!r}")
        
        # Allow time for data-binding and animations to settle
        time.sleep(2)
        
        print(f"✅ Capture point located. Exporting to: {output_path}")
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        card.screenshot(path=output_path)
        browser.close()
    
    print("✨ Snapshot Complete.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 snapshot_evidence.py <scenario_id> [output_path]")
        sys.exit(1)
    
    sid = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else f"evidence/{sid.replace('/', '_')}.png"
    
    snapshot_scenario(sid, out)
