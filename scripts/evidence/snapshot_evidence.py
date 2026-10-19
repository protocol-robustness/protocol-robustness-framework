import sys
import os
import time
import re
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
    
    clerk_url = "http://localhost:7777/notebooks/workbench_v2"
    
    print("📸 Initializing Snapshot Engine")
    print(f"🔗 Accessing URL: {clerk_url}")
    
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport=viewport, device_scale_factor=2)
        page = context.new_page()
        
        # Navigate to the Clerk notebook
        page.goto(clerk_url)
        
        print("⏳ Waiting for the requested scenario card (max 60s)...")
        page.wait_for_selector("details.scenario-details", timeout=60000)
        wanted = normalized_scenario_id(scenario_id)
        deadline = time.monotonic() + 60
        card = None
        while card is None and time.monotonic() < deadline:
            card = next(
                (candidate for candidate in page.locator("details.scenario-details").all()
                 if normalized_scenario_id(candidate.locator("summary").inner_text()).find(wanted) >= 0),
                None,
            )
            if card is None:
                time.sleep(0.5)
        if card is None:
            raise RuntimeError(f"Scenario {scenario_id!r} is not present in the workbench")
        if not card.evaluate("element => element.open"):
            card.locator("summary").click()
        
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
    
    # Ensure evidence directory exists
    os.makedirs("evidence", exist_ok=True)
    
    snapshot_scenario(sid, out)
