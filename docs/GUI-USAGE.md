# GUI Target/Destination File Selection - Quick Reference

## Updated Features in Compress/Decompress View

### Input File Selection
- **Browse Button**: Click "Browse..." next to the input file field to select a source file
- **Text Field**: Shows the selected input file path
- **Auto-updates**: Output path is automatically generated when input is selected

### Output File Selection  
- **Browse Button**: Click "Browse..." next to the output file field to choose where to save
- **Text Field**: 
  - Auto-filled with smart default (input filename + `.dc` extension for compression)
  - Can be manually edited to specify custom output path
  - For decompression, removes `.dc` extension automatically
- **Save Dialog**: Opens with suggested filename based on operation type

### Workflow

#### Compression:
1. Click "Browse..." next to Input File
2. Select the file you want to compress
3. Output field auto-fills with `<filename>.dc` in same directory
4. (Optional) Click "Browse..." next to Output to change destination
5. Click "Compress" button

#### Decompression:
1. Click "Browse..." next to Input File
2. Select the `.dc` compressed file
3. Output field auto-fills with original filename (`.dc` removed)
4. (Optional) Click "Browse..." next to Output to change destination
5. Click "Decompress" button

### Smart Features

✅ **Auto-detection**: Automatically detects if file is compressed (`.dc` extension)  
✅ **Enable/Disable**: Compress button enabled for any file, Decompress only for `.dc` files  
✅ **Path suggestions**: Smart default output paths based on operation  
✅ **Manual override**: Both input and output paths can be manually edited  
✅ **Directory creation**: Output directories created automatically if needed  

### Example Paths

**Compression:**
- Input: `/home/user/documents/data.tar`
- Output: `/home/user/documents/data.tar.dc` (auto-generated)
- Can change to: `/backup/compressed/archive.dc`

**Decompression:**
- Input: `/backup/compressed/archive.dc`
- Output: `/backup/compressed/archive` (auto-generated)
- Can change to: `/home/user/restored/data.tar`

### Progress Tracking

Both operations show:
- Progress bar (0-100%)
- Throughput in MB/s
- Estimated time remaining (ETA)
- Status messages (success/error)

### Settings
- **Force CPU mode**: Checkbox to disable GPU acceleration and use CPU-only compression
