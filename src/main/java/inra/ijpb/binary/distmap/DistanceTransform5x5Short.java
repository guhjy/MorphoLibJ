/*-
 * #%L
 * Mathematical morphology library and plugins for ImageJ/Fiji.
 * %%
 * Copyright (C) 2014 - 2017 INRA.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package inra.ijpb.binary.distmap;

import static java.lang.Math.min;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import inra.ijpb.algo.AlgoStub;
import inra.ijpb.algo.AlgoEvent;

/**
 * <p>Computes Chamfer distances in a 5x5 neighborhood using a ShortProcessor 
 * object for storing result.</p>
 * 
 * <p>Uses 5x5 chamfer map, with two passes (one forward, one backward).
 * Three different weights are provided for orthogonal, diagonal, and 
 * "chess-knight" moves. Weights equal to (5,7,11) usually give nice results.
 * </p>
 * 
 * <p>
 * Example of use:
 *<pre>{@code
 *	short[] shortWeights = ChamferWeights.CHESSKNIGHT.getShortWeights();
 *	boolean normalize = true;
 *	DistanceTransform dt = new DistanceTransform5x5Short(shortWeights, normalize);
 *	ImageProcessor result = dt.distanceMap(inputImage);
 *	// or:
 *	ImagePlus resultPlus = BinaryImages.distanceMap(imagePlus, shortWeights, normalize);
 *}</pre>
 * 
 * @see inra.ijpb.binary.BinaryImages#distanceMap(ImageProcessor, short[], boolean)
 * @see inra.ijpb.binary.distmap.DistanceTransform5x5Float
 * @see inra.ijpb.binary.distmap.DistanceTransform
 * 
 * @author David Legland
 */
public class DistanceTransform5x5Short extends AlgoStub implements DistanceTransform 
{
	private final static int DEFAULT_MASK_LABEL = 255;

	private short[] weights = new short[]{3, 4, 5};

	private int width;
	private int height;

	private ImageProcessor maskProc;

	int maskLabel = DEFAULT_MASK_LABEL;

	/**
	 * Flag for dividing final distance map by the value first weight. 
	 * This results in distance map values closer to Euclidean, but with 
	 * non integer values. 
	 */
	private boolean normalizeMap = true;

	/**
	 * The inner buffer that will store the distance map. The content
	 * of the buffer is updated during forward and backward iterations.
	 */
	private ShortProcessor buffer;
	
	/**
	 * Default constructor with predefined chamfer weights.
	 */
	public DistanceTransform5x5Short() 
	{
		this(new short[]{5, 7, 11}, true);
	}

	/**
	 * Default constructor that specifies the chamfer weights.
	 * @param weights an array of two weights for orthogonal and diagonal directions
	 */
	public DistanceTransform5x5Short(short[] weights) 
	{
		this(weights, true);
	}

	/**
	 * Constructor specifying the chamfer weights and the optional normalization.
	 * @param weights
	 *            an array of two weights for orthogonal and diagonal directions
	 * @param normalize
	 *            flag indicating whether the final distance map should be
	 *            normalized by the first weight
	 */
	public DistanceTransform5x5Short(short[] weights, boolean normalize)
	{
		if (weights.length < 3) 
		{
			short[] newWeights = new short[3];
			newWeights[0] = weights[0];
			newWeights[1] = weights[1];
			newWeights[2] =(short) (weights[0] + weights[1]);
			weights = newWeights;
		}
		this.weights = weights;
		this.normalizeMap = normalize;
	}

	/**
	 * Computes the distance map of the distance to the nearest boundary pixel.
	 * The function returns a new short processor the same size as the input,
	 * with values greater or equal to zero. 
	 * 
	 * @param image a binary image with white pixels (255) as foreground
	 * @return a new instance of ShortProcessor containing: <ul>
	 * <li> 0 for each background pixel </li>
	 * <li> the distance to the nearest background pixel otherwise</li>
	 * </ul>
	 */
	public ShortProcessor distanceMap(ImageProcessor image)
	{
		// size of image
		width = image.getWidth();
		height = image.getHeight();
		
		// update mask
		this.maskProc = image;

		// create new empty image, and fill it with black
		buffer = new ShortProcessor(width, height);
		buffer.setValue(0);
		buffer.fill();
		
		this.fireStatusChanged(new AlgoEvent(this, "Initialization"));
		
		// initialize empty image with either 0 (background) or Inf (foreground)
		for (int i = 0; i < width; i++)
		{
			for (int j = 0; j < height; j++)
			{
				int val = image.get(i, j) & 0x00ff;
				buffer.set(i, j, val == 0 ? 0 : Short.MAX_VALUE);
			}
		}
		
		// Two iterations are enough to compute distance map to boundary
		this.fireStatusChanged(new AlgoEvent(this, "Forward Scan"));
		forwardIteration();
		this.fireStatusChanged(new AlgoEvent(this, "Backward Scan"));
		backwardIteration();

		// Normalize values by the first weight
		if (this.normalizeMap) 
		{
			this.fireStatusChanged(new AlgoEvent(this, "Normalization"));
			for (int i = 0; i < width; i++) 
			{
				for (int j = 0; j < height; j++)
				{
					if (maskProc.getPixel(i, j) != 0) 
					{
						buffer.set(i, j, buffer.get(i, j) / weights[0]);
					}
				}
			}
		}
		
		this.fireStatusChanged(new AlgoEvent(this, ""));

		// Compute max value within the mask
		short maxVal = 0;
		for (int i = 0; i < width; i++)
		{
			for (int j = 0; j < height; j++) 
			{
				if (maskProc.getPixel(i, j) != 0)
					maxVal = (short) Math.max(maxVal, buffer.get(i, j));
			}
		}
		
		// calibrate min and max values of result image processor
		buffer.setMinAndMax(0, maxVal);

		// Forces the display to non-inverted LUT
		if (buffer.isInvertedLut())
			buffer.invertLut();
		
		return buffer;
	}

	private void forwardIteration()
	{
		// variables declaration
		int ortho;
		int diago;
		int diag2;
		int newVal;

		// Process first line: consider only the pixel on the left
		for (int i = 1; i < width; i++) 
		{
			if (maskProc.get(i, 0) != maskLabel)
				continue;
			ortho = buffer.get(i - 1, 0) + weights[0];
			updateIfNeeded(i, 0, ortho );
		}

		// process first pixel of second line: up, upright, and (+2,-1)
		if (maskProc.get(0, 1) == maskLabel) 
		{
			ortho = buffer.get(0, 0) + weights[0];
			diago = buffer.get(1, 0) + weights[1];
			diag2 = buffer.get(2, 0) + weights[2];
			newVal = min3(ortho, diago, diag2);
			updateIfNeeded(0, 1, newVal);
		}
		
		// process second pixel of second line: up, left, upleft and upright, and (+2,-1)
		if (maskProc.get(1, 1) == maskLabel) 
		{
			ortho = min(buffer.get(0, 1), buffer.get(1, 0));
			diago = min(buffer.get(0, 0), buffer.get(2, 0));
			diag2 = buffer.get(3, 0);
			newVal = min3w(ortho, diago, diag2);
			updateIfNeeded(1, 1, newVal);
		}

		// Second line, regular pixels: consider only the pixel on the left
		// and from the first line
		for (int i = 2; i < width - 2; i++) 
		{
			if (maskProc.get(i, 1) != maskLabel)
				continue;
			ortho = min(buffer.get(i - 1, 1), buffer.get(i, 0));
			diago = min(buffer.get(i-1, 0), buffer.get(i+1, 0));
			diag2 = min(buffer.get(i-2, 0), buffer.get(i+2, 0));
			newVal = min3w(ortho, diago, diag2);
			updateIfNeeded(i, 1, newVal);
		}
		
		// last pixel of second line
		if (maskProc.get(width-1, 1) == maskLabel) 
		{
			ortho = min(buffer.get(width-2, 1), buffer.get(width-1, 0));
			diago = buffer.get(1, 0);
			diag2 = buffer.get(2, 0);
			newVal = min3w(ortho, diago, diag2);
			updateIfNeeded(width-1, 1, newVal);
		}

		// Process all other lines
		for (int j = 2; j < height; j++) 
		{
			this.fireProgressChanged(this, j, height);

			// process first pixel of current line: consider pixels up and
			// upright
			if (maskProc.get(0, j) == maskLabel) 
			{
				ortho = buffer.get(0, j - 1);
				diago = buffer.get(1, j - 1);
				diag2 = min(buffer.get(2, j - 1), buffer.get(1, j - 2));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(0, j, newVal);
			}

			//  process second pixel of regular line 
			if (maskProc.get(1, j) == maskLabel)
			{
				ortho = min(buffer.get(0, j), buffer.get(1, j - 1));
				diago = min(buffer.get(0, j - 1), buffer.get(2, j - 1));
				diag2 = min3(buffer.get(0, j - 2), buffer.get(2, j - 2), buffer.get(3, j - 1));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(1, j, newVal);
			}
			
			// Process pixels in the middle of the line
			for (int i = 2; i < width - 2; i++) 
			{
				// process only pixels inside structure
				if (maskProc.get(i, j) != maskLabel)
					continue;

				// minimum distance of neighbor pixels
				ortho = min(buffer.get(i - 1, j), buffer.get(i, j - 1));
				diago = min(buffer.get(i - 1, j - 1), buffer.get(i + 1, j - 1));
				diag2 = min(
						min(buffer.get(i - 1, j - 2), buffer.get(i + 1, j - 2)),
						min(buffer.get(i - 2, j - 1), buffer.get(i + 2, j - 1)));
				
				// compute new distance of current pixel
				newVal = min3w(ortho, diago, diag2);

				// modify current pixel if needed
				updateIfNeeded(i, j, newVal);
			}

			// penultimate pixel 
			if (maskProc.getPixel(width - 2, j) == maskLabel)
			{
				ortho = min(buffer.get(width - 3, j), buffer.get(width - 2, j - 1));
				diago = min(buffer.get(width - 3, j - 1), buffer.get(width - 1, j - 1));
				diag2 = min3(
						buffer.get(width - 4, j - 1), buffer.get(width - 3, j - 2), 
						buffer.get(width - 1, j - 2));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(width - 2, j, newVal);
			}
			
			// process last pixel of current line: consider pixels left,
			// up-left, and up
			if (maskProc.getPixel(width - 1, j) == maskLabel) 
			{
				ortho = min(buffer.get(width - 2, j), buffer.get(width - 1, j - 1));
				diago = buffer.get(width - 2, j - 1);
				diag2 = min(buffer.get(width - 3, j - 1), buffer.get(width - 2, j - 2));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(width - 1, j, newVal);
			}

		} // end of processing for current line 
		this.fireProgressChanged(this, height, height);

	} // end of forward iteration

	private void backwardIteration()
	{
		// variables declaration
		int ortho;
		int diago;
		int diag2;
		int newVal;

		// Process last line: consider only the pixel just after (on the right)
		for (int i = width - 2; i >= 0; i--) 
		{
			if (maskProc.getPixel(i, height - 1) != maskLabel)
				continue;

			newVal = buffer.get(i + 1, height - 1) + weights[0];
			updateIfNeeded(i, height - 1, newVal);
		}

		// last pixel of penultimate line: consider the 3 pixels below
		if (maskProc.getPixel(width - 1, height - 2) == maskLabel) 
		{
			ortho = buffer.get(width - 1, height - 1);
			diago = buffer.get(width - 2, height - 1);
			diag2 = buffer.get(width - 3, height - 1);
			newVal = min3w(ortho, diago, diag2);
			updateIfNeeded(width - 1, height - 2, newVal);
		}

		// penultimate pixel of penultimate line: consider right pixel, and the 4 pixels below
		if (maskProc.getPixel(width - 2, height - 2) == maskLabel) 
		{
			ortho = min(buffer.get(width - 1, height - 2), buffer.get(width - 2, height - 1));
			diago = min(buffer.get(width - 1, height - 1), buffer.get(width - 3, height - 1));
			diag2 = buffer.get(width - 4, height - 1);
			newVal = min3w(ortho, diago, diag2);
			updateIfNeeded(width - 2, height - 2, newVal);
		}

		// Process regular pixels of penultimate line
		for (int i = width - 3; i > 1; i--) 
		{
			if (maskProc.getPixel(i, height - 2) != maskLabel)
				continue;

			// minimum distance of neighbor pixels
			ortho = min(buffer.get(i + 1, height - 2), buffer.get(i, height - 1));
			diago = min(buffer.get(i - 1, height - 1), buffer.get(i + 1, height - 1));
			diag2 = min(buffer.get(i - 2, height - 1), buffer.get(i + 2, height - 1));
			
			// compute new distance of current pixel
			newVal = min3w(ortho, diago, diag2);

			// modify current pixel if needed
			updateIfNeeded(i, height - 2, newVal);
		}
		
		// Process second pixel of penultimate line
		if (maskProc.getPixel(1, height - 2) == maskLabel)
		{
			// minimum distance of neighbor pixels
			ortho = min(buffer.get(2, height - 2), buffer.get(1, height - 1));
			diago = min(buffer.get(0, height - 1), buffer.get(2, height - 1));
			diag2 = buffer.get(3, height - 1);

			// compute new distance of current pixel
			newVal = min3w(ortho, diago, diag2);

			// modify current pixel if needed
			updateIfNeeded(1, height - 2, newVal);
		}

		// Process first pixel of penultimate line
		if (maskProc.getPixel(0, height - 2) == maskLabel)
		{
			// minimum distance of neighbor pixels
			ortho = min(buffer.get(1, height - 2), buffer.get(0, height - 1));
			diago = buffer.get(1, height - 1);
			diag2 = buffer.get(2, height - 1);

			// compute new distance of current pixel
			newVal = min3w(ortho, diago, diag2);

			// modify current pixel if needed
			updateIfNeeded(0, height - 2, newVal);
		}
			
		// Process regular lines
		for (int j = height - 3; j >= 0; j--) 
		{
			this.fireProgressChanged(this, height-1-j, height);

			// process last pixel of the current line: consider pixels
			// down, down-left and (-2,+1)
			if (maskProc.getPixel(width - 1, j) == maskLabel)
			{
				ortho = buffer.get(width - 1, j + 1);
				diago = buffer.get(width - 2, j + 1);
				diag2 = min(buffer.get(width - 3, j + 1), buffer.get(width - 2, j + 2));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(width - 1, j, newVal);
			}

		
			// process penultimate pixel of current line
			if (maskProc.getPixel(width - 2, j) == maskLabel)
			{

				// minimum distance of neighbor pixels
				ortho = min(buffer.get(width - 1, j), buffer.get(width - 2, j + 1));
				diago = min(buffer.get(width - 3, j + 1), buffer.get(width - 1, j + 1));
				diag2 = min3(
						buffer.get(width - 3, j + 2), 
						buffer.get(width - 1, j + 2), 
						buffer.get(width - 4, j + 1));
				
				// compute new distance of current pixel
				newVal = min3w(ortho, diago, diag2);

				// modify current pixel if needed
				updateIfNeeded(width - 2, j, newVal);
			}

			// Process pixels in the middle of the current line
			for (int i = width - 3; i > 1; i--) 
			{
				// process only pixels inside structure
				if (maskProc.getPixel(i, j) != maskLabel)
					continue;

				// minimum distance of neighbor pixels
				ortho = min(buffer.get(i + 1, j), buffer.get(i, j + 1));
				diago = min(buffer.get(i - 1, j + 1), buffer.get(i + 1, j + 1));
				diag2 = min(
						min(buffer.get(i - 1, j + 2), buffer.get(i + 1, j + 2)),
						min(buffer.get(i - 2, j + 1), buffer.get(i + 2, j + 1)));
				
				// compute new distance of current pixel
				newVal = min3w(ortho, diago, diag2);

				// modify current pixel if needed
				updateIfNeeded(i, j, newVal);
			}

			// process second pixel of current line: consider pixels right,
			// down-right and down
			if (maskProc.getPixel(1, j) == maskLabel) 
			{
				ortho = min(buffer.get(2, j), buffer.get(1, j + 1));
				diago = min(buffer.get(0, j + 1), buffer.get(2, j + 1));
				diag2 = min3(buffer.get(3, j + 1), buffer.get(2, j + 2), buffer.get(0, j + 2));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(1, j, newVal);
			}

			// process first pixel of current line: consider pixels right,
			// down-right and down
			if (maskProc.getPixel(0, j) == maskLabel)
			{
				ortho = min(buffer.get(1, j), buffer.get(0, j + 1));
				diago = buffer.get(1, j + 1);
				diag2 = min(buffer.get(2, j + 1), buffer.get(1, j + 2));
				newVal = min3w(ortho, diago, diag2);
				updateIfNeeded(0, j, newVal);
			}

		} // end of processing for current line
		
		this.fireProgressChanged(this, height, height);
	} // end of backward iteration

	/**
	 * Computes the minimum within 3 values.
	 */
	private final static int min3(int v1, int v2, int v3)
	{
		return min(min(v1, v2), v3);
	}
	
	/**
	 * Computes the weighted minima of orthogonal, diagonal, and (2,1)-diagonal
	 * values.
	 */
	private int min3w(int ortho, int diago, int diag2)
	{
		return min(min(ortho + weights[0], diago + weights[1]), 
				diag2 + weights[2]);
	}
	
	/**
	 * Update the pixel at position (i,j) with the value newVal. If newVal is
	 * greater or equal to current value at position (i,j), do nothing.
	 */
	private void updateIfNeeded(int i, int j, int newVal)
	{
		int value = buffer.get(i, j);
		if (newVal < value) 
		{
			buffer.set(i, j, newVal);
		}
	}
}
