import os

try:
    import pyperclip
    from tqdm import tqdm
except ImportError as e:
    print(f"Error importing library: {e}")
    exit()

print("Libraries loaded successfully.")

# List of folders to exclude from the blueprint
EXCLUDE_FOLDERS = ["node_modules", "__pycache__"]

def count_total_items(root_path):
    """
    Counts total items (files + directories) with a progress bar for real-time feedback,
    excluding folders in the EXCLUDE_FOLDERS list.
    """
    total_items = 0
    with tqdm(desc="Counting items", unit="item") as progress_bar:
        for dirpath, dirnames, filenames in os.walk(root_path):
            # Modify dirnames in-place to skip excluded folders
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_FOLDERS]
            
            total_items += len(dirnames) + len(filenames)
            progress_bar.update(len(dirnames) + len(filenames))  # Update progress for each item counted
    return total_items

def generate_structure(root_path, total_items):
    """
    Generates the folder structure as a blueprint string with a progress bar,
    excluding folders in the EXCLUDE_FOLDERS list.
    """
    blueprint = []

    with tqdm(total=total_items, desc="Generating folder structure", unit="item") as progress_bar:
        for dirpath, dirnames, filenames in os.walk(root_path):
            # Modify dirnames in-place to skip excluded folders
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_FOLDERS]

            # Calculate the depth for indentation
            depth = dirpath.replace(root_path, "").count(os.sep)
            indent = "│   " * (depth - 1) if depth > 0 else ""
            folder_name = os.path.basename(dirpath) or os.path.basename(root_path)

            # Append the folder name with tree structure
            if depth == 0:
                blueprint.append(f"{folder_name}/")  # Root folder
            else:
                blueprint.append(f"{indent}├── {folder_name}/")

            progress_bar.update(1)  # Update progress for the directory itself

            # Append files in the current directory, excluding mdl.py
            for idx, filename in enumerate(sorted(filenames)):
                if filename != "mdl.py":  # Exclude the script itself
                    file_indent = indent + ("│   " if depth > 0 else "")
                    connector = "└── " if idx == len(filenames) - 1 else "├── "
                    blueprint.append(f"{file_indent}{connector}{filename}")
                    progress_bar.update(1)  # Update progress for each file

    return "\n".join(blueprint)

# Root directory (where the script is located)
root_dir = os.path.dirname(os.path.abspath(__file__))
print(f"Root directory: {root_dir}")

# First, count all items and show progress during counting
total_items = count_total_items(root_dir)
print(f"Total items to process: {total_items}")

# Then, generate the structure with a progress bar for each item processed
structure = generate_structure(root_dir, total_items)

# Copy the result to the clipboard
try:
    pyperclip.copy(structure)
    print("Folder structure copied to clipboard!")
except Exception as e:
    print(f"Error copying to clipboard: {e}")
