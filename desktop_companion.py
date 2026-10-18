import subprocess
import threading
import tkinter as tk
from tkinter import messagebox
import sys
import os

class WebcamPhoneCompanion:
    def __init__(self, root):
        self.root = root
        self.root.title("WebcamPhone Desktop Companion")
        self.root.geometry("450x400")
        self.root.minsize(400, 380)

        self.ffmpeg_process = None

        # Title
        title_label = tk.Label(root, text="WebcamPhone Desktop Companion", font=("Helvetica", 15, "bold"))
        title_label.pack(pady=15)

        # Status Frame
        self.status_var = tk.StringVar(value="Status: Disconnected")
        status_label = tk.Label(root, textvariable=self.status_var, font=("Helvetica", 11, "bold"), fg="blue")
        status_label.pack(pady=5)

        # Buttons Frame
        btn_frame = tk.Frame(root)
        btn_frame.pack(pady=15)

        self.btn_adb = tk.Button(btn_frame, text="1. Connect USB (ADB Forward)", command=self.run_adb_forward, width=32, bg="#e0e0e0", font=("Helvetica", 10))
        self.btn_adb.pack(pady=5)

        self.btn_cam = tk.Button(btn_frame, text="2. Start Virtual Webcam", command=self.start_webcam, width=32, bg="#4CAF50", fg="white", font=("Helvetica", 10, "bold"))
        self.btn_cam.pack(pady=5)

        self.btn_stop = tk.Button(btn_frame, text="Stop Webcam Stream", command=self.stop_webcam, width=32, bg="#f44336", fg="white", font=("Helvetica", 10))
        self.btn_stop.pack(pady=5)

        # Instructions
        instruction_text = (
            "Instructions:\n"
            "1. Plug in phone via USB (with USB Debugging enabled).\n"
            "2. Open WebcamPhone app on phone & tap 'Start Stream'.\n"
            "3. Click 'Connect USB' then 'Start Virtual Webcam'.\n"
            "4. Select 'WebcamPhone' in Zoom, Teams, or OBS."
        )
        instruction_label = tk.Label(root, text=instruction_text, justify=tk.LEFT, fg="gray", font=("Helvetica", 9))
        instruction_label.pack(pady=10)

    def run_adb_forward(self):
        try:
            res = subprocess.run(["adb", "forward", "tcp:8080", "tcp:8080"], capture_output=True, text=True)
            if res.returncode == 0:
                self.status_var.set("Status: ADB Connected & Forwarded (Port 8080)")
                messagebox.showinfo("Success", "USB ADB Forwarding established successfully!")
            else:
                messagebox.showerror("Error", f"Failed to run adb forward:\n{res.stderr}")
        except FileNotFoundError:
            messagebox.showerror("Error", "ADB is not installed or not in system PATH.")

    def start_webcam(self):
        if self.ffmpeg_process and self.ffmpeg_process.poll() is None:
            messagebox.showwarning("Warning", "Virtual webcam is already running!")
            return

        if sys.platform.startswith("linux"):
            video_device = "/dev/video0"
            for i in range(10):
                dev = f"/dev/video{i}"
                if os.path.exists(dev):
                    video_device = dev
                    break

            cmd = ["ffmpeg", "-i", "http://localhost:8080/video", "-pix_fmt", "yuv420p", "-f", "v4l2", video_device]
        else:
            messagebox.showinfo("Windows Info", "On Windows, please use OBS Virtual Camera with URL http://localhost:8080")
            return

        def run_ffmpeg():
            try:
                self.ffmpeg_process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.status_var.set(f"Status: Streaming to {video_device} 🟢")
                self.ffmpeg_process.wait()
            except Exception as e:
                self.status_var.set("Status: Stream Error 🔴")
                messagebox.showerror("FFmpeg Error", str(e))

        threading.Thread(target=run_ffmpeg, daemon=True).start()

    def stop_webcam(self):
        if self.ffmpeg_process and self.ffmpeg_process.poll() is None:
            self.ffmpeg_process.terminate()
            self.ffmpeg_process = None
            self.status_var.set("Status: Stopped")
            messagebox.showinfo("Stopped", "Virtual webcam stream stopped.")
        else:
            messagebox.showinfo("Info", "No active stream running.")

if __name__ == "__main__":
    root = tk.Tk()
    app = WebcamPhoneCompanion(root)
    root.mainloop()
