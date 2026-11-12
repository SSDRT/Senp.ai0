import copy
import math
import unittest

from measured_triangle import triangle_angles


class MeasuredTriangleTests(unittest.TestCase):
    def test_right_triangle_retains_all_three_original_vertices(self):
        self.assertEqual((90.,45.,45.), triangle_angles([[0,0,0],[1,0,0],[0,1,0]]))

    def test_permuting_point_order_permutates_angles_without_selecting_best(self):
        p = [[0,0,0],[2,0,0],[0,1,0]]
        original = triangle_angles(p)
        self.assertEqual(tuple(reversed(original)), triangle_angles(list(reversed(p))))

    def test_rigid_translation_and_rotation_preserve_angles(self):
        original = [[0,0,0],[2,0,0],[0,1,0]]
        transformed = [[y+100,-x+200,z-30] for x,y,z in original]
        self.assertEqual(triangle_angles(original), triangle_angles(transformed))

    def test_large_and_small_scales_do_not_use_a_fitted_length_cutoff(self):
        original = [[0,0,0],[2,0,0],[0,1,0]]
        for scale in [1e-100, 1e100]:
            for a,b in zip(triangle_angles(original), triangle_angles([[x*scale for x in p] for p in original])):
                self.assertAlmostEqual(a,b,places=10)

    def test_missing_nonfinite_and_boolean_values_cannot_make_an_angle(self):
        for value in [None, True, math.nan, math.inf, '1.0']:
            with self.subTest(value=value):
                self.assertIsNone(triangle_angles([[value,0,0],[1,0,0],[0,1,0]]))

    def test_coincident_or_incomplete_points_are_not_reconstructed(self):
        for p in [[[0,0,0],[0,0,0],[1,0,0]], [[0,0],[1,0],[0,1]], [[0,0,0],[1,0,0]]]:
            self.assertIsNone(triangle_angles(p))

    def test_collinearity_remains_zero_and_180_without_chosen_projection_plane(self):
        self.assertEqual((0.,180.,0.), triangle_angles([[0,0,0],[1,0,0],[2,0,0]]))

    def test_measured_input_is_not_repaired_or_overwritten(self):
        p = [[0,0,0],[1,0,0],[0,1,0]];before = copy.deepcopy(p)
        triangle_angles(p)
        self.assertEqual(before,p)
