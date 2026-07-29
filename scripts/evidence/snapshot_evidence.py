import sys
import os
import time
from playwright.sync_api import sync_playwright

def snapshot_scenario(scenario_id, output_path="evidence/snapshot.png"):
    """
    Captures a high-resolution screenshot of a specific scenario from the 
    Production Evidence Workbench.
    """
    # 1. Update selection file for Clerk to re-evaluate
    print(f"📝 Selecting scenario: {scenario_id}")
    with open("notebooks/snapshot_selection.edn", "w") as f:
        f.write(f'{{:selected-scenario "{scenario_id}"}}')
    
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
        
        # Wait for the SPEDS components to render and verify the ID matches
        print("⏳ Waiting for SPEDS primitives and Proof Footer (max 60s)...")
        
        # Wait for the specific scenario to be rendered in the header
        page.wait_for_selector(f"text={scenario_id}", timeout=60000)
        page.wait_for_selector(".golden-frame")
        
        # Allow time for data-binding and animations to settle
        time.sleep(2)
        
        # Locate the specific frame or carousel
        # For a full 4-frame carousel capture
        element = page.query_selector(".frame-carousel")
        if not element:
            # Fallback to the full container if no carousel found
            element = page.query_selector(".workbench-container")
            
        if element:
            print(f"✅ Capture point located. Exporting to: {output_path}")
            element.screenshot(path=output_path)
        else:
            print("❌ ERROR: Could not locate evidence frame.")
            sys.exit(1)
            
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
